package com.edward.laya.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;


/**
 * Downloads the model through the system DownloadManager: survives the app being closed,
 * resumes after network drops, follows Hugging Face's CDN redirects and shows a notification.
 */
public final class ModelDownloader {

    public static final class Status {
        public long downloaded;
        public long total = ModelFiles.totalBytes();
        public boolean running;
        public boolean allDone;
        public String error;
        public int pausedReason;
    }

    private static final String PREFS = "laya_download";
    private static final String KEY_IDS = "ids";

    private final Context app;
    private final DownloadManager dm;
    private final SharedPreferences prefs;

    public ModelDownloader(Context c) {
        this.app = c.getApplicationContext();
        this.dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasActiveDownload() {
        return !prefs.getString(KEY_IDS, "").isEmpty();
    }

    public void start(boolean wifiOnly) {
        cancel();
        ModelFiles.deleteAll(app);
        StringBuilder ids = new StringBuilder();
        for (ModelFiles.Spec s : ModelFiles.FILES) {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(s.url()))
                    .setTitle("Laya · " + s.localName)
                    .setDescription(ModelFiles.DISPLAY_NAME)
                    .setDestinationInExternalFilesDir(app, null, ModelFiles.SUBDIR + "/" + s.localName)
                    .setAllowedOverMetered(!wifiOnly)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.addRequestHeader("User-Agent", "LayaLocal-Android/0.1");
            long id = dm.enqueue(r);
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
        }
        prefs.edit().putString(KEY_IDS, ids.toString()).apply();
    }

    public void cancel() {
        long[] ids = ids();
        if (ids.length > 0) dm.remove(ids);
        prefs.edit().remove(KEY_IDS).apply();
    }

    /** Forget the finished download records without deleting our files. */
    public void clearFinished() {
        prefs.edit().remove(KEY_IDS).apply();
    }

    public Status query() {
        Status st = new Status();
        long[] ids = ids();
        if (ids.length == 0) return st;
        int done = 0;
        long bytes = 0;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(ids))) {
            if (c == null) return st;
            int found = 0;
            while (c.moveToNext()) {
                found++;
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                bytes += Math.max(0, so);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    done++;
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    st.error = "Falha no download (código " + reason + ")";
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    st.pausedReason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    st.running = true;
                } else {
                    st.running = true;
                }
            }
            if (found < ids.length && st.error == null) st.error = "Download cancelado pelo sistema";
        }
        st.downloaded = bytes;
        st.allDone = done == ids.length;
        return st;
    }

    private long[] ids() {
        String s = prefs.getString(KEY_IDS, "");
        if (s.isEmpty()) return new long[0];
        String[] p = s.split(",");
        long[] out = new long[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Long.parseLong(p[i]);
        return out;
    }
}
