package com.tenvinc.instafood;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.widget.Toast;

import com.tenvinc.instafood.env.Logger;
import com.tenvinc.instafood.tflite.Classifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link FoodDBQueryFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class FoodDBQueryFragment extends Fragment {
    public static final Logger LOGGER = new Logger();
    public static final String TAG = "FoodDBQuery";

    // Manifest permissions
    private static final int PERMISSIONS_REQUEST = 2;
    private static final String PERMISSION_INTERNET = Manifest.permission.INTERNET;
    private static final String PERMISSION_NETSTATEACESS = Manifest.permission.ACCESS_NETWORK_STATE;

    // Params to query USDA database
    private static final String API_KEY = "e33RpCtcsCnCrMQMqCiGvfguR1gGf9ChN5vWEaTl";
    private static final String API_SEARCH_URL = "https://api.nal.usda.gov/ndb/search/";
    private static final String API_REPORT_URL = "https://api.nal.usda.gov/ndb/reports/";

    private Activity mActivity;


    public FoodDBQueryFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment FoodDBQueryFragment.
     */
    public static FoodDBQueryFragment newInstance() {
        return new FoodDBQueryFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);  // headless fragment mode
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof FoodDBQueryCallback) {
            mActivity = activity;
        } else {
            throw new IllegalArgumentException("Activity must implement FoodDBQueryCallback");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }

//    public void computeCalories(String search) {
//        requestAllPermissions(PERMISSION_INTERNET, PERMISSION_NETSTATEACESS);
//        if (checkNetworkConnection()) {
//            ComputeCaloriesAsync task = new ComputeCaloriesAsync((FoodDBQueryCallback) mActivity);
//            task.execute();
//        }
//    }

    public void computeCalories(List<Classifier.Recognition> items) {
        requestAllPermissions(PERMISSION_INTERNET, PERMISSION_NETSTATEACESS);
        if (checkNetworkConnection()) {
            ComputeCaloriesAsync task = new ComputeCaloriesAsync((FoodDBQueryCallback) mActivity, items);
            task.execute();
        }
    }

    public boolean checkNetworkConnection() {
        ConnectivityManager connMgr = (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        boolean isConnected = false;

        if (networkInfo != null && (isConnected = networkInfo.isConnected())) {
            Log.i(TAG, "Connected to " + networkInfo.getTypeName());
        } else {
            Log.i(TAG, "Warning! Failed to connect to internet");
        }

        return isConnected;
    }

    private void requestAllPermissions(String... permissions) {
        for (String p : permissions) {
            if (hasPermission(p)) {
                Log.i(TAG, "Permission " + p + " has been obtained.");
            } else {
                requestPermission(p);
            }
        }
    }

    private void requestPermission(String permsString) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(permsString)) {
                Toast.makeText(
                        getActivity(),
                        permsString + " permission is required for this app",
                        Toast.LENGTH_LONG)
                        .show();
            }
            requestPermissions(new String[]{permsString}, PERMISSIONS_REQUEST);
        }
    }

    private boolean hasPermission(String manifestPerm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getActivity().checkSelfPermission(manifestPerm) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    public interface FoodDBQueryCallback {
        void startWaiting();

        void displayRes(String res);

        void updateTracker(List<Classifier.Recognition> results);
    }

    private static String HttpGet(String url, String queryString) throws IOException, JSONException {
        URL fullUrl = new URL(url + queryString);

        HttpURLConnection conn = (HttpURLConnection) fullUrl.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        conn.connect();

        String reply;

        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            bufferedReader.close();
            reply = sb.toString();
        } finally {
            conn.disconnect();
        }

        if (reply != null) {
            return reply;
        }

        return conn.getResponseMessage();
    }

    private static String formulateQueryReq(String apiKey, String itemToQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("?q=" + itemToQuery);
        sb.append("&api_key=" + apiKey);

        return sb.toString();
    }

    private static JSONObject getJsonFromString(String toTest) {
        try {
            JSONObject obj = new JSONObject(toTest);
            return obj;
        } catch (JSONException e) {
            return null; //string is not JSON so nothing to return
        }
    }

    private static String formulateReportQuery(String apiKey, String ndbno) {
        StringBuilder sb = new StringBuilder();
        sb.append("?ndbno=" + ndbno);
        sb.append("&api_key=" + apiKey);

        return sb.toString();
    }

    private static class ComputeCaloriesAsync extends AsyncTask<Void, Void, String> {

        private FoodDBQueryCallback mCallbackActivity;
        private final List<Classifier.Recognition> itemsDetected;

        public ComputeCaloriesAsync(FoodDBQueryCallback activity, List<Classifier.Recognition> itemsDetected) {
            mCallbackActivity = activity;
            this.itemsDetected = itemsDetected;
        }

        @Override
        protected void onPreExecute() {
            //mCallbackActivity.startWaiting();
            Log.i(TAG, "Starting calorie crawling...");
        }

        @Override
        protected String doInBackground(Void... params) {
            for (Classifier.Recognition item : itemsDetected) {
                String label = item.getTitle();
                try {
                    try {
                        String searchQuery = formulateQueryReq(API_KEY, label);
                        String res = HttpGet(API_SEARCH_URL, searchQuery);

                        JSONObject resObj;
                        if ((resObj = getJsonFromString(res)) == null) {
                            return null;
                        }

                        resObj = resObj.getJSONObject("list");
                        int numHits = resObj.getInt("total");

                        JSONArray items = resObj.getJSONArray("item");

                        // TODO: implement some kind of logic to get the best result
                        JSONObject bestRes = (JSONObject) items.get(0);
                        String ndbno = bestRes.getString("ndbno");

                        // Start second part to get report
                        String reportQuery = formulateReportQuery(API_KEY, ndbno);
                        res = HttpGet(API_REPORT_URL, reportQuery);

                        if ((resObj = getJsonFromString(res)) == null) {
                            return null;
                        }
                        JSONObject report = (JSONObject) resObj.get("report");
                        JSONArray nutrientList = ((JSONObject) report.get("food")).getJSONArray("nutrients");

                        float calories = -1;
                        for (int i=0; i<nutrientList.length(); i++) {
                            JSONObject nutrient = nutrientList.getJSONObject(i);
                            if (nutrient.getString("name").equals("Energy")
                                && nutrient.getString("unit").equals("kcal")) {
                                // look for cubic inch
                                double calPer100g = nutrient.getDouble("value");
                                calories = (float) calPer100g; // Can afford to lose some precision cuz not used for computation
                                break;
                            }
                        }

                        // TODO: Remove this stub and replace with actual implementation
                        item.setCaloriesCount(calories); // inplace update of list
                    } catch (JSONException e) {
                        e.printStackTrace();
                        return "Error!";
                    }
                } catch (IOException e) {
                    return "Unable to retrieve web page. URL may be invalid";
                }
            }
            return "finished";
        }

        @Override
        protected void onPostExecute(String result) {
            if (result == null) {
                Log.i(TAG, "Cannot retrieve food item result");
            }
            //mCallbackActivity.displayRes(result);
            Log.i(TAG, "Updating the view...");
            mCallbackActivity.updateTracker(itemsDetected);
        }
    }
}
