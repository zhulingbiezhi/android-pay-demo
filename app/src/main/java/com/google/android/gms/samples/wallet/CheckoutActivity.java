/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.samples.wallet;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wallet.AutoResolveHelper;
import com.google.android.gms.wallet.PaymentData;
import com.google.android.gms.wallet.PaymentDataRequest;
import com.google.android.gms.wallet.PaymentMethodToken;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.WalletConstants;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class CheckoutActivity extends Activity {
    // Arbitrarily-picked result code.
    private static final int LOAD_PAYMENT_DATA_REQUEST_CODE = 991;

    private PaymentsClient mPaymentsClient;

    private View mPwgButton;
    private TextView mPwgStatusText;

    private ItemInfo mBikeItem = new ItemInfo("Simple Bike",  10000, R.drawable.bike);
    private long mShippingCost = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkout);

        // Set up the mock information for our item in the UI.
        initItemUI();

        mPwgButton = findViewById(R.id.pwg_button);
        mPwgStatusText = findViewById(R.id.pwg_status);

        mPwgButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPayment(view);
            }
        });

        // It's recommended to create the PaymentsClient object inside of the onCreate method.
        mPaymentsClient = PaymentsUtil.createPaymentsClient(this);
        checkIsReadyToPay();
    }

    private void checkIsReadyToPay() {
        // The call to isReadyToPay is asynchronous and returns a Task. We need to provide an
        // OnCompleteListener to be triggered when the result of the call is known.
        PaymentsUtil.isReadyToPay(mPaymentsClient).addOnCompleteListener(
                new OnCompleteListener<Boolean>() {
                    public void onComplete(Task<Boolean> task) {
                        try {
                            boolean result = task.getResult(ApiException.class);
                            setPwgAvailable(result);
                        } catch (ApiException exception) {
                            // Process error
                            Log.w("isReadyToPay failed", exception);
                        }
                    }
                });
    }

    private void setPwgAvailable(boolean available) {
        // If isReadyToPay returned true, show the button and hide the "checking" text. Otherwise,
        // notify the user that Pay with Google is not available.
        // Please adjust to fit in with your current user flow. You are not required to explicitly
        // let the user know if isReadyToPay returns false.
        if (available) {
            mPwgStatusText.setVisibility(View.GONE);
            mPwgButton.setVisibility(View.VISIBLE);
        } else {
            mPwgStatusText.setText(R.string.pwg_status_unavailable);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case LOAD_PAYMENT_DATA_REQUEST_CODE:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        PaymentData paymentData = PaymentData.getFromIntent(data);
                        handlePaymentSuccess(paymentData);
                        break;
                    case Activity.RESULT_CANCELED:
                        // Nothing to here normally - the user simply cancelled without selecting a
                        // payment method.
                        break;
                    case AutoResolveHelper.RESULT_ERROR:
                        Status status = AutoResolveHelper.getStatusFromIntent(data);
                        handleError(status.getStatusCode());
                        break;
                }

                // Re-enables the Pay with Google button.
                mPwgButton.setClickable(true);
                break;
        }
    }

    private void handlePaymentSuccess(PaymentData paymentData) {
        // PaymentMethodToken contains the payment information, as well as any additional
        // requested information, such as billing and shipping address.
        //
        // Refer to your processor's documentation on how to proceed from here.
        PaymentMethodToken token = paymentData.getPaymentMethodToken();

        // getPaymentMethodToken will only return null if PaymentMethodTokenizationParameters was
        // not set in the PaymentRequest.
        if (token != null) {
            String billingName = paymentData.getCardInfo().getBillingAddress().getName();
            Toast.makeText(this, getString(R.string.payments_show_name, billingName), Toast.LENGTH_LONG).show();
            final String tokenStr = token.getToken();
            new Thread(new Runnable() {
                public void run() {
                    postToken(getPublicKeyHash(),getBase64Blob(tokenStr));
                };

            }).start();
            // Use token.getToken() to get the token string.
            Log.d("PaymentData", "PaymentMethodToken received");
        }
    }
    private String getBase64Blob(String token) {
        byte[] encodedTokenBytes = Base64.encode(token.getBytes(), Base64.NO_WRAP);
        String encodedToken = new String(encodedTokenBytes);
        return encodedToken;
        //return new String(encoded);
    }

    private String getPublicKeyHash() {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        byte[] publicKeyHash;
        byte[] pubKeyBytes = Base64.decode(Constants.DIRECT_TOKENIZATION_PUBLIC_KEY, Base64.NO_WRAP);
        publicKeyHash = digest.digest(pubKeyBytes);
        String publicKeyHashString = new String(Base64.encode(publicKeyHash, Base64.NO_WRAP));
        return publicKeyHashString;
    }

    protected void postToken(String... params) {

        HttpURLConnection conn = null;
        URL url = null;
        try {
            url = new URL("http://192.168.22.26:9090/androidPay"); //in the real code, there is an ip and a port
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);//连接的超时时间
            conn.setReadTimeout(5000);
            conn.setUseCaches(false);//不使用缓存
            conn.setRequestMethod("POST");

            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            //conn.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("publicKeyHash", params[0]);
            jsonParam.put("version", "1.0");
            jsonParam.put("data", params[1]);

            Log.i("JSON", jsonParam.toString());
            //androidPayBlobView.setText(jsonParam.toString());
            OutputStream out = conn.getOutputStream();// 获得一个输出流,向服务器写数据
            out.write(jsonParam.toString().getBytes());
            out.flush();
            //out.close();

//            Log.i("STATUS", String.valueOf(conn.getResponseCode()));
//            Log.i("MSG" , conn.getResponseMessage());
            if(conn.getResponseCode()==HttpURLConnection.HTTP_OK) {
                Log.i("success", "123");
            }else{
                Log.i("fail","123");
            }
            //conn.disconnect();

        } catch (Exception e) {

        }
    }



    private void handleError(int statusCode) {
        // At this stage, the user has already seen a popup informing them an error occurred.
        // Normally, only logging is required.
        // statusCode will hold the value of any constant from CommonStatusCode or one of the
        // WalletConstants.ERROR_CODE_* constants.
        Log.w("loadPaymentData failed", String.format("Error code: %d", statusCode));
    }

    // This method is called when the Pay with Google button is clicked.
    public void requestPayment(View view) {
        // Disables the button to prevent multiple clicks.
        mPwgButton.setClickable(false);

        // The price provided to the API should include taxes and shipping.
        // This price is not displayed to the user.
        String price = PaymentsUtil.microsToString(mBikeItem.getPriceMicros() + mShippingCost);

        TransactionInfo transaction = PaymentsUtil.createTransaction(price);
        PaymentDataRequest request = PaymentsUtil.createPaymentDataRequestDirect(transaction);
        Task<PaymentData> futurePaymentData = mPaymentsClient.loadPaymentData(request);

        // Since loadPaymentData may show the UI asking the user to select a payment method, we use
        // AutoResolveHelper to wait for the user interacting with it. Once completed,
        // onActivityResult will be called with the result.
        AutoResolveHelper.resolveTask(futurePaymentData, this, LOAD_PAYMENT_DATA_REQUEST_CODE);
    }

    private void initItemUI() {
        TextView itemName = findViewById(R.id.text_item_name);
        ImageView itemImage = findViewById(R.id.image_item_image);
        TextView itemPrice = findViewById(R.id.text_item_price);

        itemName.setText(mBikeItem.getName());
        itemImage.setImageResource(mBikeItem.getImageResourceId());
        itemPrice.setText(PaymentsUtil.microsToString(mBikeItem.getPriceMicros()));
    }
}
