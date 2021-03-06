package de.nico.asura;

/* 
 * Author: Nico Alt
 * See the file "LICENSE" for the full license governing this code.
 */

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import de.nico.asura.activities.WebView2;
import de.nico.asura.activities.Preferences;
import de.nico.asura.activities.WebView1;
import de.nico.asura.activities.WebView3;
import de.nico.asura.tools.JSONParser;
import de.nico.asura.tools.Utils;

public final class Main extends Activity {

    // Log tag for this class
    private static final String TAG = "Main";

    // Callback code for request of storage permission
    final private int PERMISSIONS_REQUEST_STORAGE = 735;

    // JSON Node Names
    private static final String TAG_TYPE = "plans";
    private static final String TAG_NAME = "name";
    private static final String TAG_FILENAME = "filename";
    private static final String TAG_URL = "url";
    private static final String TAG_SHOULD_NOT_CACHE = "shouldNotCache";

    // Files to delete
    private static final ArrayList<String> filesToGetDeleted = new ArrayList<>();

    // Strings displayed to the user
    private static String offline;
    private static String noPDF;
    private static String localLoc;
    private static String jsonURL;

    // PDF Download
    private static DownloadManager downloadManager;
    private static long downloadID;
    private static File file;

    private static SwipeRefreshLayout swipeRefreshLayout;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context arg0, Intent arg1) {
            final DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadID);
            final Cursor cursor = downloadManager.query(query);

            if (cursor.moveToFirst()) {
                final int columnIndex = cursor
                        .getColumnIndex(DownloadManager.COLUMN_STATUS);
                final int status = cursor.getInt(columnIndex);

                switch (status) {
                    case DownloadManager.STATUS_SUCCESSFUL:
                        final Uri path = Uri.fromFile(file);
                        final Intent pdfIntent = new Intent(Intent.ACTION_VIEW);
                        pdfIntent.setDataAndType(path, "application/pdf");
                        pdfIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                        try {
                            startActivity(pdfIntent);
                        } catch (ActivityNotFoundException e) {
                            Utils.makeLongToast(Main.this, noPDF);
                            Log.e(TAG, e.getMessage());
                        }
                        break;
                    case DownloadManager.STATUS_FAILED:
                        Utils.makeLongToast(Main.this,
                                getString(R.string.down_error));
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        Utils.makeLongToast(Main.this,
                                getString(R.string.down_paused));
                        break;
                    case DownloadManager.STATUS_PENDING:
                        Utils.makeLongToast(Main.this,
                                getString(R.string.down_pending));
                        break;
                    case DownloadManager.STATUS_RUNNING:
                        Utils.makeLongToast(Main.this,
                                getString(R.string.down_running));
                        break;
                }
            }
        }
    };
    // Data from JSON file
    private ArrayList<HashMap<String, String>> downloadList = new ArrayList<>();

    private static void checkDir() {
        final File dir = new File(Environment.getExternalStorageDirectory() + "/"
                + localLoc + "/");
        if (!dir.exists()) {
            dir.mkdir();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        update(false, true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_prefs:
                startActivity(new Intent(this, Preferences.class));
                return true;

            case R.id.action_WebView1:
                if (Utils.isNoNetworkAvailable(this)) {
                    Utils.makeLongToast(this, offline);
                } else {
                    startActivity(new Intent(this, WebView1.class));
                }
                return true;

            case R.id.action_WebView2:
                if (Utils.isNoNetworkAvailable(this)) {
                    Utils.makeLongToast(this, offline);
                } else {
                    startActivity(new Intent(this, WebView2.class));
                }
                return true;

            case R.id.action_WebView3:
                if (Utils.isNoNetworkAvailable(this)) {
                    Utils.makeLongToast(this, offline);
                } else {
                    startActivity(new Intent(this, WebView3.class));
                }
                return true;

            case R.id.action_Link1:
                if (Utils.isNoNetworkAvailable(this)) {
                    Utils.makeLongToast(this, offline);
                } else {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.menu_Link_1_url))));
                }
                return true;

            case R.id.action_Link2:
                if (Utils.isNoNetworkAvailable(this)) {
                    Utils.makeLongToast(this, offline);
                } else {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.menu_Link_2_url))));
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        final IntentFilter intentFilter = new IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(downloadReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(downloadReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Delete files which should not get cached.
        for (String file : filesToGetDeleted) {
            final File f = new File(file);
            if (f.exists()) {
                f.delete();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Utils.makeLongToast(Main.this, getString(R.string.permission_write_external_storage_success));
                }
                else {
                    final String permission_write_external_storage_failure = getString(R.string.permission_write_external_storage_failure);
                    final String app_title = getString(R.string.gen_name);
                    final String message = String.format(permission_write_external_storage_failure, app_title);
                    Utils.makeLongToast(Main.this, message);

                    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(Main.this, permissions[0]);
                    if (!showRationale) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    }
                }
                break;
        }
    }

    private void update(boolean force, boolean firstLoad) {
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        swipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeRefreshLayout_main);

        offline = getString(R.string.status_offline);
        noPDF = getString(R.string.except_nopdf);
        localLoc = getString(R.string.gen_loc);
        jsonURL = getString(R.string.gen_json);

        checkDir();

        if (firstLoad) {
            try {
                downloadList = (ArrayList<HashMap<String, String>>) Utils.readObject(this, "downloadList");
                if (downloadList != null) {
                    setList(true, true);
                }
            } catch (ClassNotFoundException | IOException e) {
                Log.e(TAG, e.getMessage());
            }
        }

        // Parse the JSON file of the plans from the URL
        JSONParse j = new JSONParse();
        j.force = force;
        j.online = !Utils.isNoNetworkAvailable(this);
        j.execute();
    }

    private void setList(final boolean downloadable, final boolean itemsAvailable) {
        if (itemsAvailable) {
            try {
                Utils.writeObject(this, "downloadList", downloadList);
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
            }
        }
        final ListView list = (ListView) findViewById(R.id.listView_main);
        final ListAdapter adapter = new SimpleAdapter(this, downloadList,
                android.R.layout.simple_list_item_1, new String[]{TAG_NAME},
                new int[]{android.R.id.text1});
        list.setAdapter(adapter);

        // React when user click on item in the list
        if (itemsAvailable) {
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {

                @Override
                public void onItemClick(AdapterView<?> parent, View v, int pos,
                                        long id) {
                    int hasStoragePermission = ContextCompat.checkSelfPermission(Main.this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    if (hasStoragePermission != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(Main.this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                PERMISSIONS_REQUEST_STORAGE);
                        return;
                    }
                    final Uri downloadUri = Uri.parse(downloadList.get(pos).get(TAG_URL));
                    final String title = downloadList.get(pos).get(TAG_NAME);
                    final String shouldNotCache = downloadList.get(pos).get(TAG_SHOULD_NOT_CACHE);
                    file = new File(Environment.getExternalStorageDirectory() + "/"
                            + localLoc + "/"
                            + downloadList.get(pos).get(TAG_FILENAME) + ".pdf");
                    final Uri dst = Uri.fromFile(file);

                    if (file.exists()) {
                        final Intent pdfIntent = new Intent(Intent.ACTION_VIEW);
                        pdfIntent.setDataAndType(dst, "application/pdf");
                        pdfIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                        try {
                            startActivity(pdfIntent);
                        } catch (ActivityNotFoundException e) {
                            Utils.makeLongToast(Main.this, noPDF);
                            Log.e(TAG, e.getMessage());
                        }
                        return;
                    }
                    if (downloadable && !Utils.isNoNetworkAvailable(Main.this)) {
                        // Download PDF
                        final Request request = new Request(downloadUri);
                        request.setTitle(title).setDestinationUri(dst);
                        downloadID = downloadManager.enqueue(request);
                        if (shouldNotCache.equals("true")) {
                            filesToGetDeleted.add(file.toString());
                        }
                    } else {
                        Utils.makeLongToast(Main.this, offline);
                    }
                }
            });
        }

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                update(true, false);
            }
        });
    }

    private class JSONParse extends AsyncTask<String, String, JSONObject> {

        public boolean force = false;
        public boolean online = false;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            swipeRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    swipeRefreshLayout.setRefreshing(true);
                }
            });
        }

        @Override
        protected JSONObject doInBackground(String... args) {
            return JSONParser.getJSONFromUrl(Main.this, jsonURL, force, online);
        }

        @Override
        protected void onPostExecute(JSONObject json) {

            swipeRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    swipeRefreshLayout.setRefreshing(false);
                }
            });

            downloadList.clear();
            if (json == null) {
                String error = getString(R.string.except_json);
                if (!online) {
                    error = offline;
                }
                final HashMap<String, String> map = new HashMap<>();
                map.put(TAG_NAME, error);
                downloadList.add(map);

                setList(false, false);
                return;
            }

            try {

                // Get JSON Array from URL
                final JSONArray j_plans = json.getJSONArray(TAG_TYPE);

                for (int i = 0; i < j_plans.length(); i++) {
                    final JSONObject c = j_plans.getJSONObject(i);

                    // Storing JSON item in a Variable
                    final String ver = c.getString(TAG_FILENAME);
                    final String name = c.getString(TAG_NAME);
                    final String api = c.getString(TAG_URL);
                    String shouldNotCache = "";
                    if (c.has(TAG_SHOULD_NOT_CACHE)) {
                        shouldNotCache = c.getString(TAG_SHOULD_NOT_CACHE);
                    }

                    // Adding value HashMap key => value
                    final HashMap<String, String> map = new HashMap<>();
                    map.put(TAG_FILENAME, ver);
                    map.put(TAG_NAME, name);
                    map.put(TAG_URL, api);
                    map.put(TAG_SHOULD_NOT_CACHE, shouldNotCache);
                    downloadList.add(map);

                    setList(online, true);
                }
            } catch (JSONException e) {
                Log.e(TAG, e.getMessage());
            }
        }
    }
}
