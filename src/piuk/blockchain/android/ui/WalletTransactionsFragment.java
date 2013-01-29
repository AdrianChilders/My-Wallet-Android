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

package piuk.blockchain.android.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.ViewPager;
import android.text.format.DateUtils;
import android.view.*;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.google.bitcoin.core.*;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import piuk.blockchain.R;
import piuk.blockchain.android.AddressBookProvider;
import piuk.blockchain.android.BlockchainService;
import piuk.blockchain.android.WalletApplication;
import piuk.blockchain.android.util.ViewPagerTabs;

import java.math.BigInteger;
import java.text.DateFormat;
import java.util.*;

/**
 * @author Andreas Schildbach
 */
public final class WalletTransactionsFragment extends Fragment
{
	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.wallet_transactions_fragment, container, false);

		final ViewPagerTabs pagerTabs = (ViewPagerTabs) view.findViewById(R.id.transactions_pager_tabs);
		pagerTabs.addTabLabels(R.string.wallet_transactions_fragment_tab_received, R.string.wallet_transactions_fragment_tab_all,
				R.string.wallet_transactions_fragment_tab_sent);

		final PagerAdapter pagerAdapter = new PagerAdapter(getFragmentManager());

		final ViewPager pager = (ViewPager) view.findViewById(R.id.transactions_pager);
		pager.setAdapter(pagerAdapter);
		pager.setOnPageChangeListener(pagerTabs);
		pager.setCurrentItem(1);
		pager.setPageMargin(2);
		pager.setPageMarginDrawable(R.color.background_less_bright);
		pagerTabs.onPageScrolled(1, 0, 0); // should not be needed

		return view;
	}

	private static class PagerAdapter extends FragmentStatePagerAdapter
	{
		public PagerAdapter(final FragmentManager fm)
		{
			super(fm);
		}

		@Override
		public int getCount()
		{
			return 3;
		}

		@Override
		public Fragment getItem(final int position)
		{
			return ListFragment.instance(position);
		}
	}

	private static class TransactionsLoader extends AsyncTaskLoader<List<Transaction>>
	{
		private final WalletApplication application;

		private TransactionsLoader(final Context context, final WalletApplication application)
		{
			super(context);

			this.application = application;
		}

		@Override
		protected void onStartLoading()
		{
			super.onStartLoading();

			application.getWallet().addEventListener(walletEventListener);

			forceLoad();
		}

		@Override
		protected void onStopLoading()
		{
			application.getWallet().removeEventListener(walletEventListener);

			super.onStopLoading();
		}

		@Override
		public List<Transaction> loadInBackground()
		{
			final List<Transaction> transactions = new ArrayList<Transaction>(application.getWallet().getTransactions(true, false));

			Collections.sort(transactions, TRANSACTION_COMPARATOR);

			return transactions;
		}

		private final WalletEventListener walletEventListener = new AbstractWalletEventListener()
		{
			@Override
			public void onChange(/*Wallet wallet*/)
			{
				try {
					forceLoad();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		};

		private static final Comparator<Transaction> TRANSACTION_COMPARATOR = new Comparator<Transaction>()
				{
			public int compare(final Transaction tx1, final Transaction tx2)
			{
				final boolean pending1 = tx1.getConfidence().getConfidenceType() == ConfidenceType.NOT_SEEN_IN_CHAIN;
				final boolean pending2 = tx2.getConfidence().getConfidenceType() == ConfidenceType.NOT_SEEN_IN_CHAIN;

				if (pending1 != pending2)
					return pending1 ? -1 : 1;

				final long time1 = tx1.getUpdateTime() != null ? tx1.getUpdateTime().getTime() : 0;
				final long time2 = tx2.getUpdateTime() != null ? tx2.getUpdateTime().getTime() : 0;

				if (time1 != time2)
					return time1 > time2 ? -1 : 1;

					return 0;
			}
				};
	}

	public static class ListFragment extends android.support.v4.app.ListFragment implements LoaderCallbacks<List<Transaction>>
	{
		private WalletApplication application;
		private Activity activity;
		private ArrayAdapter<Transaction> adapter;

		private int mode;

		private int bestChainHeight;

		private final Handler handler = new Handler();

		private final static String KEY_MODE = "mode";

		public static ListFragment instance(final int mode)
		{
			final ListFragment fragment = new ListFragment();

			final Bundle args = new Bundle();
			args.putInt(KEY_MODE, mode);
			fragment.setArguments(args);

			return fragment;
		}

		private final ContentObserver contentObserver = new ContentObserver(handler)
		{
			@Override
			public void onChange(final boolean selfChange)
			{
				try {
					adapter.notifyDataSetChanged();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		};

		private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(final Context context, final Intent intent)
			{
				bestChainHeight = intent.getIntExtra(BlockchainService.ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_HEIGHT, 0);

				adapter.notifyDataSetChanged();
			}
		};

		@Override
		public void onAttach(final Activity activity)
		{
			super.onAttach(activity);

			this.activity = activity;
			application = (WalletApplication) activity.getApplication();
		}

		@Override
		public void onCreate(final Bundle savedInstanceState)
		{
			super.onCreate(savedInstanceState);

			this.mode = getArguments().getInt(KEY_MODE);

			adapter = new ArrayAdapter<Transaction>(activity, 0){

				final DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(activity);
				final DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(activity);
				final int colorSignificant = getResources().getColor(R.color.significant);
				final int colorInsignificant = getResources().getColor(R.color.insignificant);
				final int colorSent = getResources().getColor(R.color.color_sent);
				final int colorReceived = getResources().getColor(R.color.color_received);

				@Override
				public View getView(final int position, View row, final ViewGroup parent)
				{
					if (row == null)
						row = getLayoutInflater(null).inflate(R.layout.transaction_row, null);

					final Transaction tx = getItem(position);
					final TransactionConfidence confidence = tx.getConfidence();
					final ConfidenceType confidenceType = confidence.getConfidenceType();

					try
					{
						final BigInteger value = tx.getValue(application.getWallet());
						final boolean sent = value.signum() < 0;


						final int textColor;
						if (confidenceType == ConfidenceType.NOT_SEEN_IN_CHAIN)
						{
							textColor = colorInsignificant;
						}
						else if (confidenceType == ConfidenceType.BUILDING)
						{

							textColor = colorSignificant;
						}
						else if (confidenceType == ConfidenceType.NOT_IN_BEST_CHAIN)
						{
							textColor = colorSignificant;
						}
						else if (confidenceType == ConfidenceType.DEAD)
						{
							textColor = Color.RED;
						}
						else
						{
							textColor = colorInsignificant;
						}

						final String address;
						if (sent)
							if (tx.getOutputs().size() == 0)
								address = "Unknown";
							else
								address = tx.getOutputs().get(0).getScriptPubKey().getToAddress().toString();
						else
							if (tx.getInputs().size() == 0)
								address = "Generation";
							else
								address = tx.getInputs().get(0).getFromAddress().toString();

						final String label = AddressBookProvider.resolveLabel(activity.getContentResolver(), address);

						final TextView rowTime = (TextView) row.findViewById(R.id.transaction_row_time);
						final Date time = tx.getUpdateTime();
						rowTime.setText(time != null ? (DateUtils.isToday(time.getTime()) ? timeFormat.format(time) : dateFormat.format(time)) : null);
						rowTime.setTextColor(textColor);

						final TextView rowLabel = (TextView) row.findViewById(R.id.transaction_row_address);
						rowLabel.setTextColor(textColor);
						rowLabel.setText(label != null ? label : address);
						rowLabel.setTypeface(label != null ? Typeface.DEFAULT : Typeface.MONOSPACE);

						final CurrencyAmountView rowValue = (CurrencyAmountView) row.findViewById(R.id.transaction_row_value);
						rowValue.setCurrencyCode(null);
						rowValue.setAmountSigned(true);
						rowValue.setTextColor(textColor);
						rowValue.setAmount(value);


						if (sent) {
							rowValue.setTextColor(colorSent);
						} else {
							rowValue.setTextColor(colorReceived);
						}

						return row;
					}
					catch (final ScriptException x)
					{
						throw new RuntimeException(x);
					}
				}
					};
					setListAdapter(adapter);

					activity.getContentResolver().registerContentObserver(AddressBookProvider.CONTENT_URI, true, contentObserver);
		}

		@Override
		public void onActivityCreated(final Bundle savedInstanceState)
		{
			super.onActivityCreated(savedInstanceState);

			getLoaderManager().initLoader(0, null, this);
		}

		@Override
		public void onViewCreated(final View view, final Bundle savedInstanceState)
		{
			super.onViewCreated(view, savedInstanceState);

			setEmptyText(getString(mode == 2 ? R.string.wallet_transactions_fragment_empty_text_sent
					: R.string.wallet_transactions_fragment_empty_text_received));

			registerForContextMenu(getListView());
		}

		@Override
		public void onResume()
		{
			super.onResume();

			activity.registerReceiver(broadcastReceiver, new IntentFilter(BlockchainService.ACTION_BLOCKCHAIN_STATE));
		}

		@Override
		public void onPause()
		{
			activity.unregisterReceiver(broadcastReceiver);

			super.onPause();
		}

		@Override
		public void onDestroy()
		{
			activity.getContentResolver().unregisterContentObserver(contentObserver);

			getLoaderManager().destroyLoader(0);

			super.onDestroy();
		}

		@Override
		public void onListItemClick(final ListView l, final View v, final int position, final long id)
		{
			final Transaction tx = adapter.getItem(position);
			editAddress(tx);
		}

		// workaround http://code.google.com/p/android/issues/detail?id=20065
		private static View lastContextMenuView;

		@Override
		public void onCreateContextMenu(final ContextMenu menu, final View v, final ContextMenuInfo menuInfo)
		{
			activity.getMenuInflater().inflate(R.menu.wallet_transactions_context, menu);

			lastContextMenuView = v;
		}

		@Override
		public boolean onContextItemSelected(final MenuItem item)
		{
			final AdapterContextMenuInfo menuInfo = (AdapterContextMenuInfo) item.getMenuInfo();
			final ListAdapter adapter = ((ListView) lastContextMenuView).getAdapter();
			final Transaction tx = (Transaction) adapter.getItem(menuInfo.position);

			switch (item.getItemId())
			{
			case R.id.wallet_transactions_context_edit_address:
				editAddress(tx);
				return true;

				/*case R.id.wallet_transactions_context_show_transaction:
					TransactionActivity.show(activity, tx);
					return true;
				 */
			default:
				return false;
			}
		}

		private void editAddress(final Transaction tx)
		{
			try
			{
				final boolean sent = tx.getValue(application.getWallet()).signum() < 0;

				Address address = null;
				if (sent) {
					if (tx.getOutputs().size() == 0)
						return;

					 address = tx.getOutputs().get(0).getScriptPubKey().getToAddress();
				} else {
					if (tx.getInputs().size() == 0)
						return;

					address = tx.getInputs().get(0).getFromAddress();
				}

				EditAddressBookEntryFragment.edit(getFragmentManager(), address.toString());
			}
			catch (final ScriptException x)
			{
				// ignore click
				x.printStackTrace();
			}
		}

		public Loader<List<Transaction>> onCreateLoader(final int id, final Bundle args)
		{
			return new TransactionsLoader(activity, application);
		}

		public void onLoadFinished(final Loader<List<Transaction>> loader, final List<Transaction> transactions)
		{
			adapter.clear();

			try
			{
				for (final Transaction tx : transactions)
				{
					final boolean sent = tx.getValue(application.getWallet()).signum() < 0;
					if ((mode == 0 && !sent) || mode == 1 || (mode == 2 && sent))
						adapter.add(tx);
				}
			}
			catch (final ScriptException x)
			{
				throw new RuntimeException(x);
			}
		}

		public void onLoaderReset(final Loader<List<Transaction>> loader)
		{
			adapter.clear();
		}
	}
}