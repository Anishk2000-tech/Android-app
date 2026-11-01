package com.workdeck.app.web;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import com.workdeck.app.call.CallManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android's real microphone / camera / audio-output state, in the shape the page
 * script uses to answer the Permissions API and enumerateDevices().
 */
final class MediaInfo {
    private static final String TAG = "WorkDeck/Media";
    private static List<String> sCameras;

    private MediaInfo() {}

    static synchronized List<String> cameras(Context c) {
        if (sCameras != null) return sCameras;
        List<String> out = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            boolean front = false;
            boolean back = false;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) front = true;
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) back = true;
            }
            // Front first: that's what video calls should pick by default.
            if (front) out.add("front");
            if (back) out.add("back");
        } catch (Throwable t) {
            Log.w(TAG, "camera list unavailable", t);
        }
        sCameras = out;
        return out;
    }

    static String json(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("mic", c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            o.put("camera", c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
            JSONArray cams = new JSONArray();
            for (String cam : cameras(c)) cams.put(cam);
            o.put("cameras", cams);
            JSONArray routes = new JSONArray();
            for (CallManager.Route r : CallManager.get(c).availableRoutes()) routes.put(r.name().toLowerCase(Locale.ROOT));
            o.put("routes", routes);
            String bt = CallManager.get(c).bluetoothName();
            if (bt != null) o.put("btName", bt);
            CallManager.Route cur = CallManager.get(c).route();
            o.put("route", cur == null ? "" : cur.name().toLowerCase(Locale.ROOT));
            return o.toString();
        } catch (Throwable t) {
            Log.w(TAG, "media info failed", t);
            return "";
        }
    }
}
