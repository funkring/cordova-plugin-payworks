package at.oerp.payworks.cordova;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.util.Log;

import io.mpos.errors.MposError;
import io.mpos.provider.ProviderMode;
import io.mpos.transactions.Transaction;
import io.mpos.transactions.parameters.TransactionParameters;
import io.mpos.ui.acquirer.ApplicationName;
import io.mpos.ui.shared.MposUi;
import io.mpos.ui.shared.model.MposUiConfiguration;

public class Payworks extends CordovaPlugin {
	
	private String TAG = "Payworks";
	private HashMap<String, PayworksPluginCmd> api;
	private ActivityCallback activityCallback;
	
		
	abstract static class PayworksPluginCmd {
		abstract boolean execute(final JSONArray args, final CallbackContext callbackContext) 
					throws Exception;		
	}
	
	abstract static class ActivityCallback {
		public final CallbackContext callbackContext;
		public final int	resultCode;

		public ActivityCallback(CallbackContext inCallbackContext, int inResultCode) {
			callbackContext = inCallbackContext;
			resultCode = inResultCode;
		}

		public void onActivityResult(int resultCode, Intent intent) {

		}
	}

	protected void pushCallback(Intent intent, ActivityCallback inCallback) {
		boolean valid = false;
		try {
			cordova.setActivityResultCallback(this);
			activityCallback = inCallback;
			cordova.startActivityForResult((CordovaPlugin) this, intent, inCallback.resultCode);
			valid = true;
		} finally {
			if ( !valid ) {
				activityCallback = null;
			}
		}
	}

	protected JSONObject toJSONError(MposError error) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("name","transaction_error");
		res.put("message",error.getInfo());
		res.put("developerInfo",error.getDeveloperInfo());
		res.putOpt("errorSource",error.getErrorSource());
		res.putOpt("errorType",error.getErrorType());
		return res;
	}

	protected JSONObject toJSONError(String message) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("name","transaction_error");
		res.put("message",message);
		return res;
	}

	protected JSONObject toJSONError(Transaction inTransaction) throws JSONException {
		MposError error = inTransaction != null ? inTransaction.getError() : null;
		if ( error != null ) {
			return toJSONError(error);
		} else {
			return toJSONError("Unable to execute transaction");
		}
	}

	protected JSONObject toJSONTransaction(Transaction inTransaction) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("transactionId", inTransaction.getIdentifier());
		res.put("amount",inTransaction.getAmount().doubleValue());
		res.put("currency",inTransaction.getCurrency());
		res.put("customIdentifier",inTransaction.getCustomIdentifier());
		res.put("subject",inTransaction.getSubject());
		res.put("status",inTransaction.getStatus());
		return res;
	}

	@Override
	public boolean execute(final String inAction, final JSONArray inArgs, final CallbackContext inCallbackContext) throws JSONException {
		try {

			// create service
			synchronized ( this ) {
				
				// init service
				if ( api == null ) {

					// init api
					api = new HashMap<String, Payworks.PayworksPluginCmd>();


					// init
					api.put("init", new PayworksPluginCmd() {
						@Override
						boolean execute(JSONArray args, CallbackContext callbackContext) throws Exception {


							ProviderMode mode = ProviderMode.TEST;
							ApplicationName appName = ApplicationName.MCASHIER;
							String integratorCode = "OERP";

							JSONObject config = args.getJSONObject(0);
							if ( config != null ) {
								// mode
								String modeStr = config.optString("mode");
								if (modeStr != null) {
									mode = ProviderMode.valueOf(modeStr);
								}

								// appname
								String appStr = config.getString("appName");
								if (appStr != null) {
									appName = ApplicationName.valueOf(appStr);
								}

								// integrator
								integratorCode = config.optString("integrator",integratorCode);
							}

							// init payworks
							MposUi mposUi = MposUi.initialize(cordova.getActivity(), mode, appName, integratorCode);

							// configure
							if ( config != null ) {

								// result
								if (config.getBoolean("displayResult")) {
									mposUi.getConfiguration().setDisplayResultBehavior(MposUiConfiguration.ResultDisplayBehavior.DISPLAY_INDEFINITELY);
								} else {
									mposUi.getConfiguration().setDisplayResultBehavior(MposUiConfiguration.ResultDisplayBehavior.CLOSE_AFTER_TIMEOUT);
								}

								// signature
								if (config.getBoolean("signatureOnReceipt")) {
									mposUi.getConfiguration().setSignatureCapture(MposUiConfiguration.SignatureCapture.ON_RECEIPT);
								} else {
									mposUi.getConfiguration().setSignatureCapture(MposUiConfiguration.SignatureCapture.ON_SCREEN);
								}

								// summary options
								JSONObject summary = config.getJSONObject("summary");
								if ( summary != null ) {
									EnumSet<MposUiConfiguration.SummaryFeature> enumSet = EnumSet.noneOf(MposUiConfiguration.SummaryFeature.class);
									if ( summary.getBoolean("captureTransaction") ) {
										enumSet.add(MposUiConfiguration.SummaryFeature.CAPTURE_TRANSACTION);
									}
									if ( summary.getBoolean("printReceipt")) {
										enumSet.add(MposUiConfiguration.SummaryFeature.PRINT_RECEIPT);
									}
									if ( summary.getBoolean("refundTransaction") ) {
										enumSet.add(MposUiConfiguration.SummaryFeature.REFUND_TRANSACTION);
									}
									if ( summary.getBoolean("email") ) {
										enumSet.add(MposUiConfiguration.SummaryFeature.SEND_RECEIPT_VIA_EMAIL);
									}
								}

							}
							return true;
						}
					});

					// payment function
					api.put("payment", new PayworksPluginCmd() {
						@Override
						boolean execute(JSONArray args, CallbackContext callbackContext) throws Exception {
							// main params
							io.mpos.transactions.Currency currency = io.mpos.transactions.Currency.EUR;

							// parse options
							JSONObject options = args.getJSONObject(0);
							BigDecimal amount = new BigDecimal(options.getDouble("amount"));
							String customId = "Payment";
							String subject = customId;
							if  ( options != null ) {
								currency = io.mpos.transactions.Currency.valueOf(options.optString("currency","EUR"));
								customId = options.optString("customId",customId);
								subject = options.optString("subject",subject);
							}


							// do payment
							TransactionParameters transactionParameters = new TransactionParameters.Builder()
									.charge(amount, currency)
									.subject(subject)
									.customIdentifier(customId)
									.build();

							MposUi mposUi = MposUi.getInitializedInstance();
							Intent intent = mposUi.createTransactionIntent(transactionParameters);

							// push callback
							pushCallback(intent, new Payworks.ActivityCallback(callbackContext, MposUi.REQUEST_CODE_PAYMENT)  {
								@Override
								public void onActivityResult(int resultCode, Intent intent) {
									try {
										Transaction transaction = MposUi.getInitializedInstance().getTransaction();
										if ( resultCode == MposUi.RESULT_CODE_APPROVED ) {
											JSONObject result = new JSONObject();
											callbackContext.success(toJSONTransaction(transaction));
										} else {
											callbackContext.error(toJSONError(transaction));
										}
									} catch ( JSONException e) {
										callbackContext.error(e.getMessage());
									}
								}
							});

							return true;
						}
					});

					// refund function
					api.put("cancelPayment", new PayworksPluginCmd() {
						@Override
						boolean execute(JSONArray args, CallbackContext callbackContext) throws Exception {
							// parse options
							JSONObject options = args.getJSONObject(0);
							String transactionId = options.getString("transationId");
							String customId = "Refund";
							String subject = customId;
							if  ( options != null ) {
								customId = options.optString("customId",customId);
								subject = options.optString("subject",subject);
							}

							// do payment
							TransactionParameters transactionParameters = new TransactionParameters.Builder()
									.refund(transactionId)
									.subject(subject)
									.customIdentifier(customId)
									.build();

							MposUi mposUi = MposUi.getInitializedInstance();
							Intent intent = mposUi.createTransactionIntent(transactionParameters);

							// push callback
							pushCallback(intent, new Payworks.ActivityCallback(callbackContext, MposUi.REQUEST_CODE_PAYMENT)  {
								@Override
								public void onActivityResult(int resultCode, Intent intent) {
									try {
										Transaction transaction = MposUi.getInitializedInstance().getTransaction();
										if ( resultCode == MposUi.RESULT_CODE_APPROVED ) {
											JSONObject result = new JSONObject();
											callbackContext.success(toJSONTransaction(transaction));
										} else {
											callbackContext.error(toJSONError(transaction));
										}
									} catch ( JSONException e) {
										callbackContext.error(e.getMessage());
									}
								}
							});

							return true;
						}
					});

					// logout
					api.put("logout", new PayworksPluginCmd() {
						@Override
						boolean execute(JSONArray args, CallbackContext callbackContext) throws Exception {
							MposUi mposUi = MposUi.getInitializedInstance();
							mposUi.logout();
							return true;
						}
					});
				}
			}

			
			// return false if command 
			// not exist
			PayworksPluginCmd cmd = api.get(inAction);
			if ( cmd == null )
				return false;
			
			// execute cmd
			return cmd.execute(inArgs, inCallbackContext);
			
		} catch ( Throwable e) {
			// log error
			String msg = e.getMessage();
			if ( msg != null ) {
				Log.e(TAG, msg);
			} else {
				msg = e.getClass().getName();
				if ( e.getCause() != null ) {
					msg = e.getCause().getMessage();
					if ( msg == null ) msg = e.getCause().getClass().getName();
				}
				Log.e(TAG, msg);
			}
			
			// throw before return
			if ( e instanceof JSONException ) {				
				throw (JSONException) e;
			}
			
			// return error via callback
			inCallbackContext.error(msg);
			return true;
		}
	}
	
	
	@Override
	public void onStop() {
		synchronized (this) {
			activityCallback = null;
		}
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		ActivityCallback callback = activityCallback;
		if ( callback != null && requestCode == callback.resultCode ) {
			callback.onActivityResult(resultCode, intent);
		} 
	}

}
