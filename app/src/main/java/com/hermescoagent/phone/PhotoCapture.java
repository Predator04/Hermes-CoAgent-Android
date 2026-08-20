package com.hermescoagent.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Silent Camera2 still capture (no preview UI). Returns a JPEG as base64 plus
 * a cached file path. Used by the "photo" and "stolen" actions.
 */
public final class PhotoCapture {

    private PhotoCapture() {}

    private static final long OPEN_TIMEOUT_MS = 5000L;
    private static final long SESSION_TIMEOUT_MS = 5000L;
    private static final long CAPTURE_TIMEOUT_MS = 6000L;
    private static final int MAX_IMAGES = 2;
    // Cap picked JPEG size around 4 MP for reasonable base64 payloads.
    private static final long SIZE_CAP_PIXELS = 4L * 1024 * 1024;

    @SuppressWarnings("MissingPermission")
    public static JSONObject capture(Context ctx, String facing) {
        JSONObject resp = new JSONObject();
        String cam = "back".equalsIgnoreCase(facing) ? "back" : "front";
        try {
            resp.put("camera", cam);
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                resp.put("ok", false);
                resp.put("error", "camera permission not granted");
                return resp;
            }
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                resp.put("ok", false);
                resp.put("error", "no camera service");
                return resp;
            }
            int wantFacing = "back".equals(cam)
                    ? CameraCharacteristics.LENS_FACING_BACK
                    : CameraCharacteristics.LENS_FACING_FRONT;
            String cameraId = null;
            CameraCharacteristics chars = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == wantFacing) {
                    cameraId = id;
                    chars = cc;
                    break;
                }
            }
            if (cameraId == null) {
                resp.put("ok", false);
                resp.put("error", "no " + cam + " camera");
                return resp;
            }
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                resp.put("ok", false);
                resp.put("error", "no stream config");
                return resp;
            }
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes == null || jpegSizes.length == 0) {
                resp.put("ok", false);
                resp.put("error", "no jpeg sizes");
                return resp;
            }
            Size picked = pickSize(jpegSizes);
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotation = sensorOrientation == null ? 0 : sensorOrientation;

            HandlerThread ht = new HandlerThread("hermes-cam-" + cam);
            ht.start();
            Handler handler = new Handler(ht.getLooper());

            final ImageReader reader = ImageReader.newInstance(
                    picked.getWidth(), picked.getHeight(), ImageFormat.JPEG, MAX_IMAGES);
            final AtomicReference<byte[]> jpegRef = new AtomicReference<>();
            final CountDownLatch imageLatch = new CountDownLatch(1);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    jpegRef.set(bytes);
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                    imageLatch.countDown();
                }
            }, handler);

            // Dummy preview surface — some devices reject a session with only
            // an ImageReader target for TEMPLATE_STILL_CAPTURE.
            final SurfaceTexture st = new SurfaceTexture(0);
            st.setDefaultBufferSize(picked.getWidth(), picked.getHeight());
            final Surface dummySurface = new Surface(st);

            final AtomicReference<CameraDevice> deviceRef = new AtomicReference<>();
            final AtomicReference<CameraCaptureSession> sessionRef = new AtomicReference<>();
            final AtomicReference<String> errorMsgRef = new AtomicReference<>();
            final CountDownLatch openLatch = new CountDownLatch(1);
            // Set on the main thread when we've given up waiting; if the camera
            // then opens late, the callback closes it immediately instead of
            // leaking a CameraDevice reference nobody will release.
            final java.util.concurrent.atomic.AtomicBoolean abandoned =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        if (abandoned.get()) {
                            try { camera.close(); } catch (Throwable ignored) {}
                            openLatch.countDown();
                            return;
                        }
                        deviceRef.set(camera);
                        openLatch.countDown();
                    }
                    @Override public void onDisconnected(CameraDevice camera) {
                        errorMsgRef.compareAndSet(null, "camera disconnected");
                        try { camera.close(); } catch (Throwable ignored) {}
                        openLatch.countDown();
                    }
                    @Override public void onError(CameraDevice camera, int error) {
                        String msg;
                        switch (error) {
                            case ERROR_CAMERA_IN_USE: msg = "camera in use"; break;
                            case ERROR_MAX_CAMERAS_IN_USE: msg = "max cameras in use"; break;
                            case ERROR_CAMERA_DISABLED: msg = "camera disabled by policy"; break;
                            case ERROR_CAMERA_DEVICE: msg = "camera device error"; break;
                            case ERROR_CAMERA_SERVICE: msg = "camera service error"; break;
                            default: msg = "camera error " + error;
                        }
                        errorMsgRef.compareAndSet(null, msg);
                        try { camera.close(); } catch (Throwable ignored) {}
                        openLatch.countDown();
                    }
                }, handler);
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                cleanup(reader, dummySurface, st, ht, null, null);
                resp.put("ok", false);
                resp.put("error", "openCamera: " + e);
                return resp;
            }

            if (!openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                abandoned.set(true);
                // Callback may have raced and stored a device between our timeout
                // and setting abandoned; close whichever side wins.
                CameraDevice late = deviceRef.getAndSet(null);
                if (late != null) try { late.close(); } catch (Throwable ignored) {}
                cleanup(reader, dummySurface, st, ht, null, null);
                resp.put("ok", false);
                resp.put("error", "camera open timeout");
                return resp;
            }
            CameraDevice device = deviceRef.get();
            if (device == null) {
                cleanup(reader, dummySurface, st, ht, null, null);
                resp.put("ok", false);
                resp.put("error", errorMsgRef.get() == null ? "camera did not open" : errorMsgRef.get());
                return resp;
            }

            final CountDownLatch sessionLatch = new CountDownLatch(1);
            List<Surface> targets = Arrays.asList(reader.getSurface(), dummySurface);
            try {
                device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        sessionRef.set(session);
                        sessionLatch.countDown();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        errorMsgRef.compareAndSet(null, "session configure failed");
                        sessionLatch.countDown();
                    }
                }, handler);
            } catch (CameraAccessException | IllegalArgumentException e) {
                cleanup(reader, dummySurface, st, ht, null, device);
                resp.put("ok", false);
                resp.put("error", "createCaptureSession: " + e);
                return resp;
            }
            if (!sessionLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cleanup(reader, dummySurface, st, ht, null, device);
                resp.put("ok", false);
                resp.put("error", "session create timeout");
                return resp;
            }
            CameraCaptureSession session = sessionRef.get();
            if (session == null) {
                cleanup(reader, dummySurface, st, ht, null, device);
                resp.put("ok", false);
                resp.put("error", errorMsgRef.get() == null ? "no session" : errorMsgRef.get());
                return resp;
            }

            try {
                CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                rb.addTarget(reader.getSurface());
                rb.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                rb.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                rb.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                rb.set(CaptureRequest.JPEG_ORIENTATION, rotation);
                session.capture(rb.build(), null, handler);
            } catch (CameraAccessException | IllegalStateException e) {
                cleanup(reader, dummySurface, st, ht, session, device);
                resp.put("ok", false);
                resp.put("error", "capture: " + e);
                return resp;
            }

            boolean got = imageLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            byte[] jpeg = jpegRef.get();
            cleanup(reader, dummySurface, st, ht, session, device);
            if (!got || jpeg == null) {
                resp.put("ok", false);
                resp.put("error", "capture timeout");
                return resp;
            }

            File dir = new File(ctx.getCacheDir(), "photos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Purge previous photos so multi-MB JPEGs don't accumulate in the
            // app-private cache (potential exposure via backup or root).
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File out = new File(dir, "photo-" + System.currentTimeMillis() + "-" + cam + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(jpeg);
            }

            resp.put("ok", true);
            resp.put("path", out.getAbsolutePath());
            resp.put("width", picked.getWidth());
            resp.put("height", picked.getHeight());
            resp.put("bytes", jpeg.length);
            resp.put("base64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            return resp;
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            return resp;
        }
    }

    private static Size pickSize(Size[] sizes) {
        Size best = null;
        long bestArea = 0;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area <= SIZE_CAP_PIXELS && area > bestArea) { best = s; bestArea = area; }
        }
        if (best != null) return best;
        Size largest = sizes[0];
        long largestArea = (long) largest.getWidth() * largest.getHeight();
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area > largestArea) { largest = s; largestArea = area; }
        }
        return largest;
    }

    private static void cleanup(ImageReader reader, Surface s, SurfaceTexture st, HandlerThread ht,
                                CameraCaptureSession session, CameraDevice device) {
        if (session != null) try { session.close(); } catch (Throwable ignored) {}
        if (device != null) try { device.close(); } catch (Throwable ignored) {}
        if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
        if (s != null) try { s.release(); } catch (Throwable ignored) {}
        if (st != null) try { st.release(); } catch (Throwable ignored) {}
        if (ht != null) try { ht.quitSafely(); } catch (Throwable ignored) {}
    }
}
