/*
 * Copyright 2011-2012 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package piuk.blockchain.android;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.*;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.Toast;
import com.google.bitcoin.core.*;
import org.apache.commons.io.IOUtils;
import piuk.BitcoinAddress;
import piuk.EventListeners;
import piuk.Hash;
import piuk.MyRemoteWallet;
import piuk.MyRemoteWallet.NotModfiedException;
import piuk.blockchain.android.R;
import piuk.blockchain.android.util.ErrorReporter;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.CookieHandler;
import java.security.Security;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Andreas Schildbach
 */
public class WalletApplication extends Application {
	private MyRemoteWallet blockchainWallet;

	private final Handler handler = new Handler();
	private Timer timer;
	public boolean hasDecryptionError = false;

	private final ServiceConnection serviceConnection = new ServiceConnection() {
		private BlockchainService service;

		public void onServiceConnected(final ComponentName name,
				final IBinder binder) {
			service = ((BlockchainService.LocalBinder) binder).getService();
		}

		public void onServiceDisconnected(final ComponentName name) {
		}
	};

	private EventListeners.EventListener eventListener = new EventListeners.EventListener() {
		@Override
		public void onWalletDidChange() {

			handler.post(new Runnable() {
				public void run() {
					try {
						localSaveWallet();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
		}
	};

	public boolean isNewWallet() {
		if (blockchainWallet == null)
			return true;

		return blockchainWallet.isNew();
	}

	public void connect() {
		if (timer != null) {
			try {
				timer.cancel();

				timer.purge();

				timer = null;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		bindService(new Intent(this, BlockchainService.class),
				serviceConnection, Context.BIND_AUTO_CREATE);
	}

	public void diconnectSoon() {
		try {
			if (timer == null) {
				timer = new Timer();

				timer.schedule(new TimerTask() {

					@Override
					public void run() {
						handler.post(new Runnable() {
							public void run() {
								try {
									unbindService(serviceConnection);
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						});
					}
				}, 2000);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
 
		ErrorReporter.getInstance().init(this);

		try {
			// Need to save session cookie for kaptcha
			@SuppressWarnings("rawtypes")
			Class aClass = getClass().getClassLoader().loadClass(
					"java.net.CookieManager");

			CookieHandler.setDefault((CookieHandler) aClass.newInstance());
			
			Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());

		} catch (Throwable e) {
			e.printStackTrace();
		}

		// If the User has a saved GUID then we can restore the wallet
		if (getGUID() != null && getSharedKey() != null) {

			// Try and read the wallet from the local cache
			if (readLocalWallet()) {
				readLocalMultiAddr();
			}

			Toast.makeText(WalletApplication.this,
					R.string.toast_downloading_wallet, Toast.LENGTH_LONG)
					.show();
		}

		// Otherwise wither first load or an error
		if (blockchainWallet == null) {
			try {
				this.blockchainWallet = new MyRemoteWallet();

				Toast.makeText(WalletApplication.this,
						R.string.toast_generated_new_wallet, Toast.LENGTH_LONG)
						.show();
			} catch (Exception e) {
				throw new Error("Could not create wallet ", e);
			}
		}

		EventListeners.addEventListener(eventListener);

		connect();
	}

	public Wallet getWallet() {
		return blockchainWallet.getBitcoinJWallet();
	}

	public MyRemoteWallet getRemoteWallet() {
		return blockchainWallet;
	}

	public String getPassword() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(
				"password", null);
	}

	public String getGUID() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(
				"guid", null);
	}

	public String getSharedKey() {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(
				"sharedKey", null);
	}

	public void notifyWidgets() {
		final Context context = getApplicationContext();

		// notify widgets
		final AppWidgetManager appWidgetManager = AppWidgetManager
				.getInstance(context);
		for (final AppWidgetProviderInfo providerInfo : appWidgetManager
				.getInstalledProviders()) {
			// limit to own widgets
			if (providerInfo.provider.getPackageName().equals(
					context.getPackageName())) {
				final Intent intent = new Intent(
						AppWidgetManager.ACTION_APPWIDGET_UPDATE);
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
						appWidgetManager.getAppWidgetIds(providerInfo.provider));
				context.sendBroadcast(intent);
			}
		}
	}

	public synchronized String readExceptionLog() {
		try {
			FileInputStream multiaddrCacheFile = openFileInput(Constants.EXCEPTION_LOG);

			return IOUtils.toString(multiaddrCacheFile);

		} catch (IOException e1) {
			e1.printStackTrace();

			return null;
		}
	}

	public synchronized void writeException(Exception e) {
		try {
			FileOutputStream file = openFileOutput(Constants.EXCEPTION_LOG,
					MODE_APPEND);

			PrintStream stream = new PrintStream(file);

			e.printStackTrace(stream);

			stream.close();

			file.close();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	public synchronized void writeMultiAddrCache(String repsonse) {
		try {
			FileOutputStream file = openFileOutput(blockchainWallet.getGUID()
					+ Constants.MULTIADDR_FILENAME, Constants.WALLET_MODE);

			file.write(repsonse.getBytes());

			file.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public synchronized void checkIfWalletHasUpdatedAndFetchTransactions() {

		if (getGUID() == null || getSharedKey() == null)
			return;
		
		new Thread(new Runnable() {
			public void run() {
				String payload = null;

				try {
					if (blockchainWallet == null)
						payload = MyRemoteWallet.getWalletPayload(getGUID(),
								getSharedKey());
					else
						payload = MyRemoteWallet.getWalletPayload(getGUID(),
								getSharedKey(), blockchainWallet.getChecksum());
				} catch (NotModfiedException e) {
					return;
				} catch (final Exception e) {
					e.printStackTrace();

					writeException(e);

					handler.post(new Runnable() {
						public void run() {
							Toast.makeText(WalletApplication.this,
									e.getLocalizedMessage(), Toast.LENGTH_SHORT)
									.show();
						}
					});
				}

				if (payload == null)
					return;

				try {
					blockchainWallet = new MyRemoteWallet(payload,
							getPassword());

					hasDecryptionError = false;

					EventListeners.invokeWalletDidChange();

				} catch (Exception e) {

					hasDecryptionError = true;

					EventListeners.invokeWalletDidChange();

					e.printStackTrace();

					writeException(e);

					handler.post(new Runnable() {
						public void run() {
							Toast.makeText(WalletApplication.this,
									R.string.toast_wallet_decryption_failed,
									Toast.LENGTH_LONG).show();
						}
					});

					return;
				}

				if (hasDecryptionError)
					return;

				// Write the wallet to the cache file
				try {
					FileOutputStream file = openFileOutput(
							Constants.WALLET_FILENAME, Constants.WALLET_MODE);
					file.write(payload.getBytes("UTF-8"));
					file.close();
				} catch (Exception e) {
					e.printStackTrace();
				}

				try {
					// Copy our labels into the address book
					if (blockchainWallet.getLabelMap() != null) {
						for (Entry<String, String> labelObj : blockchainWallet
								.getLabelMap().entrySet()) {
							AddressBookProvider.setLabel(getContentResolver(),
									labelObj.getKey(), labelObj.getValue());
						}
					}
				} catch (Exception e) {
					e.printStackTrace();

					writeException(e);
				}

				try {
					// Get the balance and transaction
					doMultiAddr();
				} catch (Exception e) {
					e.printStackTrace();

					writeException(e);

					handler.post(new Runnable() {
						public void run() {
							Toast.makeText(WalletApplication.this,
									R.string.toast_error_syncing_wallet,
									Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	public void doMultiAddr() {
		new Thread(new Runnable() {
			public void run() {
				try {
					writeMultiAddrCache(blockchainWallet.doMultiAddr());

					handler.post(new Runnable() {
						public void run() {
							notifyWidgets();
						}
					});
				} catch (Exception e) {
					e.printStackTrace();

					writeException(e);

					handler.post(new Runnable() {
						public void run() {
							Toast.makeText(
									WalletApplication.this,
									R.string.toast_error_downloading_transactions,
									Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	public static interface AddAddressCallback {
		public void onSavedAddress(String address);

		public void onError();
	}

	public void addKeyToWallet(ECKey key, String label, int tag,
			final AddAddressCallback callback) {
		try {
			blockchainWallet.addKey(key, label);

			final String address = new BitcoinAddress(new Hash(
					key.getPubKeyHash()), (short) 0).toString();

			if (tag != 0) {
				blockchainWallet.setTag(address, tag);
			}

			new Thread() {
				@Override
				public void run() {
					try {
						blockchainWallet.remoteSave();

						handler.post(new Runnable() {
							public void run() {
								callback.onSavedAddress(address);

								notifyWidgets();
							}
						});

					} catch (Exception e) {
						e.printStackTrace();

						writeException(e);

						handler.post(new Runnable() {
							public void run() {
								callback.onError();

								Toast.makeText(WalletApplication.this,
										R.string.toast_error_syncing_wallet,
										Toast.LENGTH_LONG).show();
							}
						});
					}
				}
			}.start();

		} catch (Exception e) {
			e.printStackTrace();

			writeException(e);
		}

		localSaveWallet();
	}

	public void setAddressLabel(String address, String label) {
		try {
			blockchainWallet.addLabel(address, label);

			new Thread() {
				@Override
				public void run() {
					try {
						blockchainWallet.remoteSave();
					} catch (Exception e) {
						e.printStackTrace();

						writeException(e);

						handler.post(new Runnable() {
							public void run() {
								Toast.makeText(WalletApplication.this,
										R.string.toast_error_syncing_wallet,
										Toast.LENGTH_LONG).show();
							}
						});
					}
				}
			}.start();
		} catch (Exception e) {
			e.printStackTrace();

			Toast.makeText(WalletApplication.this,
					R.string.error_setting_label, Toast.LENGTH_LONG).show();
		}
	}

	public boolean readLocalMultiAddr() {
		try {
			// Restore the multi address cache
			FileInputStream multiaddrCacheFile = openFileInput(blockchainWallet
					.getGUID() + Constants.MULTIADDR_FILENAME);

			String multiAddr = IOUtils.toString(multiaddrCacheFile);

			blockchainWallet.parseMultiAddr(multiAddr);

			return true;

		} catch (Exception e) {
			writeException(e);

			e.printStackTrace();

			return false;
		}
	}

	public boolean readLocalWallet() {
		try {
			// Read the wallet from local file
			FileInputStream file = openFileInput(Constants.WALLET_FILENAME);

			String payload = null;

			payload = IOUtils.toString(file, "UTF-8");

			MyRemoteWallet wallet = new MyRemoteWallet(payload, getPassword());

			if (wallet.getGUID().equals(getGUID())) {
				this.blockchainWallet = wallet;

				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			writeException(e);

			e.printStackTrace();

			handler.post(new Runnable() {
				public void run() {
					Toast.makeText(WalletApplication.this,
							R.string.toast_wallet_decrypt_failed,
							Toast.LENGTH_LONG).show();
				}
			});
		}

		return false;
	}

	public void localSaveWallet() {
		try {
			if (blockchainWallet.isNew())
				return;

			FileOutputStream file = openFileOutput(
					Constants.LOCAL_WALLET_FILENAME, Constants.WALLET_MODE);

			file.write(blockchainWallet.getPayload().getBytes());

			file.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public Address determineSelectedAddress() {
		final ArrayList<ECKey> keychain = getWallet().keychain;

		if (keychain.size() == 0)
			return null;

		final SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);
		final String defaultAddress = keychain.get(0)
				.toAddress(Constants.NETWORK_PARAMETERS).toString();
		final String selectedAddress = prefs.getString(
				Constants.PREFS_KEY_SELECTED_ADDRESS, defaultAddress);

		// sanity check
		for (final ECKey key : keychain) {
			final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);
			if (address.toString().equals(selectedAddress))
				return address;
		}

		return null;
	}

	public final int applicationVersionCode() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
		} catch (NameNotFoundException x) {
			return 0;
		}
	}

	public final String applicationVersionName() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (NameNotFoundException x) {
			return "unknown";
		}
	}
}
