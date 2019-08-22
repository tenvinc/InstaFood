package com.tenvinc.instafood;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.ClientError;
import com.android.volley.NetworkError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.JsonObjectRequest;
import com.tenvinc.instafood.env.Logger;
import com.tenvinc.instafood.tflite.Classifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String API_KEY = "AbtEjYICWlBZmyT7phRV21xalbvMyYeziSvfzjRI";
    private static final String API_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/search";
    private static final String API_REPORT_URL = "https://api.nal.usda.gov/fdc/v1/";

    private Activity mActivity;

    // Caching DB
    private HashMap<String, JSONObject> cachedFoodMeta;

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
        cachedFoodMeta = new HashMap<>();
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
            FoodQueryHandler handler = new FoodQueryHandler(items, (FoodDBQueryCallback) mActivity, cachedFoodMeta);
            handler.updateCalories();
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

    private class FoodQueryHandler {
        private boolean isReadyToUpdate;
        private final List<Classifier.Recognition> itemsDetected;
        private int numLeft;
        private FoodDBQueryCallback mCallbackActivity;
        private HashMap<String, JSONObject> cachedFoodMeta;

        Response.ErrorListener errorListener = new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                if (error instanceof ClientError) {
                    String str = new String(error.networkResponse.data);
                    Log.wtf(TAG, str);
                }
                if (error instanceof NetworkError) {
                    Toast.makeText(getActivity().getApplicationContext(), "No network available", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity().getApplicationContext(), error.toString(), Toast.LENGTH_LONG).show();
                }
            }
        };

        public FoodQueryHandler(List<Classifier.Recognition> itemsDetected,
                                FoodDBQueryCallback mCallbackActivity,
                                HashMap<String, JSONObject> cachedFoodMeta) {
            this.itemsDetected = itemsDetected;
            numLeft = itemsDetected.size();
            this.mCallbackActivity = mCallbackActivity;
            this.cachedFoodMeta = cachedFoodMeta;
        }

        public void updateCalories() {
            for (int i=0; i<itemsDetected.size(); i++) {
                String label = itemsDetected.get(i).getTitle();
                try {
                    if (cachedFoodMeta.containsKey(label)) {
                        Log.i(TAG, "Getting from cache " + label);
                        itemsDetected.get(i).setCaloriesCount(getCaloriesFromReport(cachedFoodMeta.get(label))); // inplace update of list
                        decrementCount();
                    } else {
                        sendSearchReq(i);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }

        public synchronized void decrementCount() {
            numLeft--;
            if (numLeft == 0) {
                Log.i(TAG, "time to update");
                this.mCallbackActivity.updateTracker(itemsDetected);
            }
        }

        public void sendSearchReq(int idx) {
            Classifier.Recognition item = itemsDetected.get(idx);

            RequestQueue queue = SingletonRequestQueue.getInstance(getActivity().getApplicationContext())
                    .getRequestQueue();

            VolleyLog.DEBUG = true;

            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("generalSearchInput", item.getTitle());
            } catch (JSONException e) {
                e.printStackTrace();
            }

            JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(Request.Method.POST, API_SEARCH_URL,
                    jsonObject, response -> {
                        Log.d(TAG, response.toString());
                        try {
                            // Find out the ndbno
                            JSONArray items = response.getJSONArray("foods");

                            // TODO: implement some kind of logic to get the best result
                            JSONObject bestRes = (JSONObject) items.get(0);
                            int fdcid = bestRes.getInt("fdcId");
                            sendReportReq(idx, fdcid);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }, errorListener) {
                @Override
                public Priority getPriority() {
                    return Priority.LOW;
                }

                @Override
                public Map<String, String> getHeaders() throws AuthFailureError {
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("x-api-key", API_KEY);
                    return headers;
                }
            };

            queue.add(jsonObjectRequest);
        }

        public float getCaloriesFromReport(JSONObject report) throws JSONException {
            JSONArray nutrientList = report.getJSONArray("foodNutrients");

            float calories = -1;
            for (int i=0; i<nutrientList.length(); i++) {
                JSONObject nutrientF = nutrientList.getJSONObject(i);
                JSONObject nutrient = nutrientF.getJSONObject("nutrient");
                if (nutrient.getString("name").equals("Energy")
                        && nutrient.getString("unitName").equals("kcal")) {
                    // look for cubic inch
                    double calPer100g = (double) nutrientF.getInt("amount");
                    calories = (float) calPer100g; // Can afford to lose some precision cuz not used for computation
                    return calories;
                }
            }
            return calories;
        }

        public void sendReportReq(int idx, int ndbno) {
            VolleyLog.DEBUG = true;
            RequestQueue queue = SingletonRequestQueue.getInstance(getActivity().getApplicationContext())
                    .getRequestQueue();

            String uri = String.format("%s%d", API_REPORT_URL, ndbno);

            JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(uri, null,
                    (Response.Listener<JSONObject>) response -> {
                        try {
                            // TODO: Remove this stub and replace with actual implementation
                            float calories = getCaloriesFromReport(response);
                            Classifier.Recognition item = itemsDetected.get(idx);
                            if (!cachedFoodMeta.containsKey(item.getTitle())) {
                                cachedFoodMeta.put(item.getTitle(), response);
                            }
                            item.setCaloriesCount(calories); // inplace update of list
                            decrementCount();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }, errorListener) {
                @Override
                public Priority getPriority() {
                    return Priority.LOW;
                }

                @Override
                public Map<String, String> getHeaders() throws AuthFailureError {
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("x-api-key", API_KEY);
                    return headers;
                }
            };
            queue.add(jsonObjectRequest);
        }
    }


}
