//// Package: replace with your app's package
//package com.example.myaccessibilityapp;
//
//import android.accessibilityservice.AccessibilityService;
//import android.accessibilityservice.AccessibilityServiceInfo;
//import android.content.Intent;
//import android.os.Build;
//import android.util.Log;
//import android.view.accessibility.AccessibilityEvent;
//import android.view.accessibility.AccessibilityNodeInfo;
//
//public class UrlAccessibilityService extends AccessibilityService {
//    private static final String TAG = "UrlAccessibilityService";
//    private String lastSentUrl = null;
//
////    @Override
//    public void onCreate() {
//        super.onCreate();
//        Log.i(TAG, "Service created");
//    }
//
//    @Override
//    public void onAccessibilityEvent(AccessibilityEvent event) {
//        int type = event.getEventType();
//        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
//                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
//                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
//            return;
//        }
//
//        AccessibilityNodeInfo root = getRootInActiveWindow();
//        if (root == null) return;
//
//        String url = UrlUtils.extractUrlFromNode(root);
//        if (url == null) {
//            CharSequence pkg = event.getPackageName();
//            if (pkg != null && pkg.toString().contains("chrome")) {
//                AccessibilityNodeInfo node = findNodeByViewId(root, "com.android.chrome:id/url_bar");
//                if (node != null && node.getText() != null) {
//                    url = node.getText().toString();
//                }
//            }
//        }
//
//        if (url != null) {
//            url = url.trim();
//            if (!url.equals(lastSentUrl)) {
//                lastSentUrl = url;
//                Log.i(TAG, "Detected URL: " + url);
//
//                Intent i = new Intent("com.example.urlgetter.ACTION_URL_DETECTED");
//                i.putExtra("url", url);
//                sendBroadcast(i);
//            }
//        }
//    }
//
//    @Override
//    public void onInterrupt() {
//        Log.w(TAG, "Accessibility service interrupted");
//    }
//
//    @Override
//    protected void onServiceConnected() {
//        super.onServiceConnected();
//        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
//        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
//                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
//                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
//        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
//        info.packageNames = null;
//        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
//                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
//            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
//        }
//        setServiceInfo(info);
//        Log.i(TAG, "Service connected and configured");
//    }
//
//    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo root, String viewId) {
//        if (root == null) return null;
//        try {
//            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
//                java.util.List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
//                if (list != null && !list.isEmpty()) return list.get(0);
//            }
//        } catch (Exception e) {
//            Log.w(TAG, "Error finding by viewId " + viewId + ": " + e);
//        }
//        return null;
//    }
//}
