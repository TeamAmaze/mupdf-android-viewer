package com.artifex.mupdf.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.res.ResourcesCompat;

import com.artifex.mupdf.fitz.SeekableInputStream;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public class DocumentActivity extends Activity
{
	private final String APP = "MuPDF";

	/* The core rendering instance */
	enum TopBarMode {Main, Search, More};

	private final int    OUTLINE_REQUEST=0;
	private MuPDFCore    core;
	private String       mDocTitle;
	private String       mDocKey;
	private ReaderView   mDocView;
	private View         mButtonsView;
	private boolean      mButtonsVisible;
	private EditText     mPasswordView;
	private TextView     mDocNameView;
	private Slider      mPageSlider;
	private int          mPageSliderRes;
	private TextView     mPageNumberView;
	private LinearLayout mHintsParentView;
	private ImageView    mHintsSwitchView;
	private ImageView    mHintsPinView;
	private ImageButton  mSearchButton;
//	private ImageButton  mOutlineButton;
	private ViewAnimator mTopBarSwitcher;
	private ImageView	 mBackButton;
	private ImageView	 mOverflowButton;
	private TopBarMode   mTopBarMode = TopBarMode.Main;
	private ImageButton  mSearchBack;
	private ImageButton  mSearchFwd;
	private ImageButton  mSearchClose;
	private EditText     mSearchText;
	private SearchTask   mSearchTask;
	private AlertDialog.Builder mAlertBuilder;
	private boolean    mLinkHighlight = false;
	private final Handler mHandler = new Handler();
	private boolean mAlertsActive= false;
	private AlertDialog mAlertDialog;
	private ArrayList<OutlineActivity.Item> mFlatOutline;
	private boolean mReturnToLibraryActivity = false;

	protected int mDisplayDPI;
	private int mLayoutEM = 10;
	private int mLayoutW = 312;
	private int mLayoutH = 504;

	private String toHex(byte[] digest) {
		StringBuilder builder = new StringBuilder(2 * digest.length);
		for (byte b : digest)
			builder.append(String.format("%02x", b));
		return builder.toString();
	}

        private MuPDFCore openBuffer(byte buffer[], String magic)
        {
                try
                {
                        core = new MuPDFCore(buffer, magic);
                }
                catch (Exception e)
                {
                        Log.e(APP, "Error opening document buffer: " + e);
                        return null;
                }
                return core;
	}

	private MuPDFCore openStream(SeekableInputStream stm, String magic)
	{
		try
		{
			core = new MuPDFCore(stm, magic);
		}
		catch (Exception e)
		{
			Log.e(APP, "Error opening document stream: " + e);
			return null;
		}
		return core;
	}

	private MuPDFCore openCore(Uri uri, long size, String mimetype) throws IOException {
		ContentResolver cr = getContentResolver();

		Log.i(APP, "Opening document " + uri);

		InputStream is = cr.openInputStream(uri);
		byte[] buf = null;
		int used = -1;
		try {
			final int limit = 8 * 1024 * 1024;
			if (size < 0) { // size is unknown
				buf = new byte[limit];
				used = is.read(buf);
				boolean atEOF = is.read() == -1;
				if (used < 0 || (used == limit && !atEOF)) // no or partial data
					buf = null;
			} else if (size <= limit) { // size is known and below limit
				buf = new byte[(int) size];
				used = is.read(buf);
				if (used < 0 || used < size) // no or partial data
					buf = null;
			}
			if (buf != null && buf.length != used) {
				byte[] newbuf = new byte[used];
				System.arraycopy(buf, 0, newbuf, 0, used);
				buf = newbuf;
			}
		} catch (OutOfMemoryError e) {
			buf = null;
		} finally {
			is.close();
		}

		if (buf != null) {
			Log.i(APP, "  Opening document from memory buffer of size " + buf.length);
			return openBuffer(buf, mimetype);
		} else {
			Log.i(APP, "  Opening document from stream");
			return openStream(new ContentInputStream(cr, uri, size), mimetype);
		}
	}

	private void showCannotOpenDialog(String reason) {
		Resources res = getResources();
		AlertDialog alert = mAlertBuilder.create();
		setTitle(String.format(Locale.ROOT, res.getString(R.string.cannot_open_document_Reason), reason));
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						finish();
					}
				});
		alert.show();
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		mDisplayDPI = (int)metrics.densityDpi;

		mAlertBuilder = new AlertDialog.Builder(this, R.style.Custom_Dialog_Dark);

		if (core == null) {
			if (savedInstanceState != null && savedInstanceState.containsKey("DocTitle")) {
				mDocTitle = savedInstanceState.getString("DocTitle");
			}
		}
		if (core == null) {
			Intent intent = getIntent();
			SeekableInputStream file;

			mReturnToLibraryActivity = intent.getIntExtra(getComponentName().getPackageName() + ".ReturnToLibraryActivity", 0) != 0;

			if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				Uri uri = intent.getData();
				String mimetype = getIntent().getType();

				if (uri == null)  {
					showCannotOpenDialog("No document uri to open");
					return;
				}

				mDocKey = uri.toString();

				Log.i(APP, "OPEN URI " + uri.toString());
				Log.i(APP, "  MAGIC (Intent) " + mimetype);

				mDocTitle = null;
				long size = -1;
				Cursor cursor = null;

				try {
					cursor = getContentResolver().query(uri, null, null, null, null);
					if (cursor != null && cursor.moveToFirst()) {
						int idx;

						idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
						if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING)
							mDocTitle = cursor.getString(idx);

						idx = cursor.getColumnIndex(OpenableColumns.SIZE);
						if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
							size = cursor.getLong(idx);

						if (size == 0)
							size = -1;
					}
				} catch (Exception x) {
					// Ignore any exception and depend on default values for title
					// and size (unless one was decoded
				} finally {
					if (cursor != null)
						cursor.close();
				}

				Log.i(APP, "  NAME " + mDocTitle);
				Log.i(APP, "  SIZE " + size);

				if (mimetype == null || mimetype.equals("application/octet-stream")) {
					mimetype = getContentResolver().getType(uri);
					Log.i(APP, "  MAGIC (Resolved) " + mimetype);
				}
				if (mimetype == null || mimetype.equals("application/octet-stream")) {
					mimetype = mDocTitle;
					Log.i(APP, "  MAGIC (Filename) " + mimetype);
				}

				try {
					core = openCore(uri, size, mimetype);
					SearchTaskResult.set(null);
				} catch (Exception x) {
					showCannotOpenDialog(x.toString());
					return;
				}
			}
			if (core != null && core.needsPassword()) {
				requestPassword(savedInstanceState);
				return;
			}
			if (core != null && core.countPages() == 0)
			{
				core = null;
			}
		}
		if (core == null)
		{
			AlertDialog alert = mAlertBuilder.create();
			alert.setTitle(R.string.cannot_open_document);
			alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			alert.setOnCancelListener(new OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					finish();
				}
			});
			alert.show();
			return;
		}

		createUI(savedInstanceState);
	}

	public void requestPassword(final Bundle savedInstanceState) {
		mPasswordView = new EditText(this);
		mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		mPasswordView.setTransformationMethod(new PasswordTransformationMethod());

		AlertDialog alert = mAlertBuilder.create();
		alert.setTitle(R.string.enter_password);
		alert.setView(mPasswordView);
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						if (core.authenticatePassword(mPasswordView.getText().toString())) {
							createUI(savedInstanceState);
						} else {
							requestPassword(savedInstanceState);
						}
					}
				});
		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
				new DialogInterface.OnClickListener() {

			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		alert.show();
	}

	public void relayoutDocument() {
		int loc = core.layout(mDocView.mCurrent, mLayoutW, mLayoutH, mLayoutEM);
		mFlatOutline = null;
		mDocView.mHistory.clear();
		mDocView.refresh();
		mDocView.setDisplayedViewIndex(loc);
	}

	public void createUI(Bundle savedInstanceState) {
		if (core == null)
			return;

		// Now create the UI.
		// First create the document view
		mDocView = new ReaderView(this) {
			@Override
			protected void onMoveToChild(int i) {
				if (core == null)
					return;

				mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", i + 1, core.countPages()));

//				mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);
				int valueTo = (core.countPages() - 1) * mPageSliderRes;
				mPageSlider.setValueTo(valueTo > 0 ? valueTo : 1);
//				mPageSlider.setProgress(i * mPageSliderRes);
				mPageSlider.setValue(i * mPageSliderRes);
				super.onMoveToChild(i);
			}

			@Override
			protected void onTapMainDocArea() {
				if (!mButtonsVisible) {
					showButtons();
				} else {
					if (mTopBarMode == TopBarMode.Main)
						hideButtons();
				}
			}

			@Override
			protected void onDocMotion() {
				hideButtons();
			}

			@Override
			public void onSizeChanged(int w, int h, int oldw, int oldh) {
				if (core.isReflowable()) {
					mLayoutW = w * 72 / mDisplayDPI;
					mLayoutH = h * 72 / mDisplayDPI;
					relayoutDocument();
				} else {
					refresh();
				}
			}
		};
		mDocView.setAdapter(new PageAdapter(this, core));

		mSearchTask = new SearchTask(this, core) {
			@Override
			protected void onTextFound(SearchTaskResult result) {
				SearchTaskResult.set(result);
				// Ask the ReaderView to move to the resulting page
				mDocView.setDisplayedViewIndex(result.pageNumber);
				// Make the ReaderView act on the change to SearchTaskResult
				// via overridden onChildSetup method.
				mDocView.resetupChildren();
			}
		};

		// Make the buttons overlay, and store all its
		// controls in variables
		makeButtonsView();

		// Set up the page slider
		int smax = Math.max(core.countPages()-1,1);
		mPageSliderRes = ((10 + smax - 1)/smax) * 2;

		// Set the file-name text
		String docTitle = core.getTitle();
		if (docTitle != null) {
			mDocNameView.setText(docTitle);
			mDocTitle = docTitle;
		}
		else {
			mDocNameView.setText(mDocTitle);
		}

		// Activate the seekbar
		mPageSlider.setStepSize(1);
		mPageSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
			@Override
			public void onStartTrackingTouch(@NonNull Slider slider) {
			}

			@Override
			public void onStopTrackingTouch(@NonNull Slider slider) {
				mPageSlider.setLabelFormatter(value -> "");
				mDocView.setDisplayedViewIndex((int)(slider.getValue()+mPageSliderRes/2)/mPageSliderRes);
			}
		});
		mPageSlider.addOnChangeListener((slider, value, fromUser) ->
				updatePageNumView((int)(value+mPageSliderRes/2)/mPageSliderRes));

		// Activate the search-preparing button
		mSearchButton.setOnClickListener(v -> searchModeOn());

		mSearchClose.setOnClickListener(v -> searchModeOff());

		// Search invoking buttons are disabled while there is no text specified
		mSearchBack.setEnabled(false);
		mSearchFwd.setEnabled(false);
		mSearchBack.setColorFilter(Color.argb(255, 128, 128, 128));
		mSearchFwd.setColorFilter(Color.argb(255, 128, 128, 128));

		// React to interaction with the text widget
		mSearchText.addTextChangedListener(new TextWatcher() {

			public void afterTextChanged(Editable s) {
				boolean haveText = s.toString().length() > 0;
				setButtonEnabled(mSearchBack, haveText);
				setButtonEnabled(mSearchFwd, haveText);

				// Remove any previous search results
				if (SearchTaskResult.get() != null && !mSearchText.getText().toString().equals(SearchTaskResult.get().txt)) {
					SearchTaskResult.set(null);
					mDocView.resetupChildren();
				}
			}
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {}
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {}
		});

		//React to Done button on keyboard
		mSearchText.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE)
				search(1);
			return false;
		});

		mSearchText.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER)
				search(1);
			return false;
		});

		// Activate search invoking buttons
		mSearchBack.setOnClickListener(v -> search(-1));
		mSearchFwd.setOnClickListener(v -> search(1));

		/*if (core.hasOutline()) {
			mOutlineButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					if (mFlatOutline == null)
						mFlatOutline = core.getOutline();
					if (mFlatOutline != null) {
						Intent intent = new Intent(DocumentActivity.this, OutlineActivity.class);
						Bundle bundle = new Bundle();
						bundle.putInt("POSITION", mDocView.getDisplayedViewIndex());
						bundle.putSerializable("OUTLINE", mFlatOutline);
						intent.putExtra("PALLETBUNDLE", Pallet.sendBundle(bundle));
						startActivityForResult(intent, OUTLINE_REQUEST);
					}
				}
			});
		} else {
			mOutlineButton.setVisibility(View.GONE);
		}*/

		// Reenstate last state if it was recorded
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		mDocView.setDisplayedViewIndex(prefs.getInt("page"+mDocKey, 0));

		if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false))
			showButtons();

		if(savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
			searchModeOn();

		// Stick the document view and the buttons overlay into a parent view
		FrameLayout layout = new FrameLayout(this);
		layout.setBackgroundColor(getResources().getColor(R.color.navy_blue));
		layout.addView(mDocView);
		layout.addView(mButtonsView);
		setContentView(layout);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case OUTLINE_REQUEST:
			if (resultCode >= RESULT_FIRST_USER && mDocView != null) {
//				mDocView.pushHistory();
				mDocView.setDisplayedViewIndex(resultCode-RESULT_FIRST_USER);
			}
			break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (mDocKey != null && mDocView != null) {
			if (mDocTitle != null)
				outState.putString("DocTitle", mDocTitle);

			// Store current page in the prefs against the file name,
			// so that we can pick it up each time the file is loaded
			// Other info is needed only for screen-orientation change,
			// so it can go in the bundle
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mDocKey, mDocView.getDisplayedViewIndex());
			edit.apply();
		}

		if (!mButtonsVisible)
			outState.putBoolean("ButtonsHidden", true);

		if (mTopBarMode == TopBarMode.Search)
			outState.putBoolean("SearchMode", true);
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mSearchTask != null)
			mSearchTask.stop();

		if (mDocKey != null && mDocView != null) {
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mDocKey, mDocView.getDisplayedViewIndex());
			edit.apply();
		}
	}

	public void onDestroy()
	{
		if (mDocView != null) {
			mDocView.applyToChildren(new ReaderView.ViewMapper() {
				void applyToView(View view) {
					((PageView)view).releaseBitmaps();
				}
			});
		}
		if (core != null)
			core.onDestroy();
		core = null;
		super.onDestroy();
	}

	private void setButtonEnabled(ImageButton button, boolean enabled) {
		button.setEnabled(enabled);
		button.setColorFilter(enabled ? Color.argb(255, 255, 255, 255) : Color.argb(255, 128, 128, 128));
	}

	private void setLinkHighlight(boolean highlight) {
		mLinkHighlight = highlight;
		// LINK_COLOR tint
		// Inform pages of the change.
		mDocView.setLinksEnabled(highlight);
	}

	private void showButtons() {
		if (core == null)
			return;
		if (!mButtonsVisible) {
			mButtonsVisible = true;
			// Update page number text and slider
			int index = mDocView.getDisplayedViewIndex();
			updatePageNumView(index);
//			mPageSlider.setMax((core.countPages()-1)*mPageSliderRes);
//			mPageSlider.setProgress(index * mPageSliderRes);
			int valueTo = (core.countPages()-1)*mPageSliderRes;
			mPageSlider.setValueTo(valueTo > 0 ? valueTo : 1);
			mPageSlider.setValue(index * mPageSliderRes);
			if (mTopBarMode == TopBarMode.Search) {
				mSearchText.requestFocus();
				showKeyboard();
			}

			Animation anim = new AlphaAnimation(0, 1);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mTopBarSwitcher.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {}
			});
			mTopBarSwitcher.startAnimation(anim);
			Animation anim2 = new AlphaAnimation(0, 1);
			anim2.setDuration(200);
			anim2.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPageSlider.setVisibility(View.INVISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
//					mPageNumberView.setVisibility(View.VISIBLE);
					if (core.countPages() > 1) {
						mPageSlider.setVisibility(View.VISIBLE);
					}
				}
			});
			Animation anim3 = new AlphaAnimation(0, 1);
			anim3.setDuration(200);
			anim3.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mHintsParentView.setVisibility(View.INVISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mHintsParentView.setVisibility(View.VISIBLE);
				}
			});
			if (core.countPages() > 1) {
				mPageSlider.startAnimation(anim2);
			}
			mHintsParentView.startAnimation(anim3);
		}
	}

	private void hideButtons() {
		if (mButtonsVisible) {
			mButtonsVisible = false;
			hideKeyboard();

			Animation anim = new AlphaAnimation(1, 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mTopBarSwitcher.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mTopBarSwitcher.setVisibility(View.INVISIBLE);
				}
			});
			mTopBarSwitcher.startAnimation(anim);

			Animation anim2 = new AlphaAnimation(1, 0);
			anim2.setDuration(200);
			anim2.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					if (core.countPages() > 1) {
						mPageSlider.setVisibility(View.VISIBLE);
					}
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mPageSlider.setVisibility(View.INVISIBLE);
				}
			});
			Animation anim3 = new AlphaAnimation(1, 0);
			anim3.setDuration(200);
			anim3.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
//					mPageNumberView.setVisibility(View.INVISIBLE);
					mHintsParentView.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mHintsParentView.setVisibility(View.INVISIBLE);
				}
			});
			if (core.countPages() > 1) {
				mPageSlider.startAnimation(anim2);
			}
			mHintsParentView.startAnimation(anim3);
		}
	}

	private void searchModeOn() {
		if (mTopBarMode != TopBarMode.Search) {
			mTopBarMode = TopBarMode.Search;
			//Focus on EditTextWidget
			mSearchText.requestFocus();
			showKeyboard();
			mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		}
	}

	private void searchModeOff() {
		if (mTopBarMode == TopBarMode.Search) {
			mTopBarMode = TopBarMode.Main;
			hideKeyboard();
			mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
			SearchTaskResult.set(null);
			// Make the ReaderView act on the change to mSearchTaskResult
			// via overridden onChildSetup method.
			mDocView.resetupChildren();
		}
	}

	private void updatePageNumView(int index) {
		if (core == null)
			return;
		mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", index + 1, core.countPages()));
		mPageSlider.setLabelFormatter(value -> index + 1 + "");
	}

	private void makeButtonsView() {
		mButtonsView = getLayoutInflater().inflate(R.layout.document_activity, null);
		mDocNameView = (TextView)mButtonsView.findViewById(R.id.docNameText);
		mPageSlider = (Slider)mButtonsView.findViewById(R.id.pageSlider);
		mHintsParentView = mButtonsView.findViewById(R.id.hints_parent);
		mPageNumberView = mHintsParentView.findViewById(R.id.page_number);
		mHintsSwitchView = mHintsParentView.findViewById(R.id.switch_view);
		mHintsPinView = mHintsParentView.findViewById(R.id.pin_view);
		mSearchButton = (ImageButton)mButtonsView.findViewById(R.id.searchButton);
//		mOutlineButton = (ImageButton)mButtonsView.findViewById(R.id.outlineButton);
		mTopBarSwitcher = (ViewAnimator)mButtonsView.findViewById(R.id.switcher);
		mSearchBack = (ImageButton)mButtonsView.findViewById(R.id.searchBack);
		mSearchFwd = (ImageButton)mButtonsView.findViewById(R.id.searchForward);
		mSearchClose = (ImageButton)mButtonsView.findViewById(R.id.searchClose);
		mSearchText = (EditText)mButtonsView.findViewById(R.id.searchText);
		mBackButton = (ImageView) mButtonsView.findViewById(R.id.back_button);
		mOverflowButton = (ImageView) mButtonsView.findViewById(R.id.overflow_button);
		mTopBarSwitcher.setVisibility(View.INVISIBLE);
//		mPageNumberView.setVisibility(View.INVISIBLE);
		mHintsParentView.setVisibility(View.INVISIBLE);

		mPageSlider.setVisibility(View.INVISIBLE);
		mHintsSwitchView.setOnClickListener(v -> {
			if (mDocKey != null && mDocView != null) {
				boolean isDarkMode = mDocView.switchDarkMode();
				if (isDarkMode) {
					mHintsSwitchView.setImageDrawable(ResourcesCompat.getDrawable(
							getResources(),
							R.drawable.ic_outline_light_mode_32, getTheme()
					));
					mHintsParentView.setBackground(ResourcesCompat.getDrawable(
							getResources(),
							R.drawable.button_curved_unselected, getTheme()
					));
				} else {
					mHintsSwitchView.setImageDrawable(ResourcesCompat.getDrawable(
							getResources(),
							R.drawable.ic_outline_dark_mode_32, getTheme()
					));
					mHintsParentView.setBackground(ResourcesCompat.getDrawable(
							getResources(),
							R.drawable.background_curved_dark_2, getTheme()
					));
				}
			}
		});
		mHintsPinView.setOnClickListener(v -> {
			if (mDocKey != null && mDocView != null) {
				mDocView.pushHistory();
				Toast.makeText(this, getString(R.string.page_pinned),
						Toast.LENGTH_LONG).show();
			}
		});
		mBackButton.setOnClickListener(v -> DocumentActivity.super.onBackPressed());
		ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(DocumentActivity.this,
				R.style.custom_action_mode_dark);

		PopupMenu mLayoutPopupMenu = new PopupMenu(
				contextThemeWrapper, mTopBarSwitcher, Gravity.END
		);
		mLayoutPopupMenu.inflate(R.menu.layout_menu);
		mLayoutPopupMenu.setOnMenuItemClickListener(item -> {
			float oldLayoutEM = mLayoutEM;
			int id = item.getItemId();
			if (id == R.id.action_layout_6pt) mLayoutEM = 6;
			else if (id == R.id.action_layout_7pt) mLayoutEM = 7;
			else if (id == R.id.action_layout_8pt) mLayoutEM = 8;
			else if (id == R.id.action_layout_9pt) mLayoutEM = 9;
			else if (id == R.id.action_layout_10pt) mLayoutEM = 10;
			else if (id == R.id.action_layout_11pt) mLayoutEM = 11;
			else if (id == R.id.action_layout_12pt) mLayoutEM = 12;
			else if (id == R.id.action_layout_13pt) mLayoutEM = 13;
			else if (id == R.id.action_layout_14pt) mLayoutEM = 14;
			else if (id == R.id.action_layout_15pt) mLayoutEM = 15;
			else if (id == R.id.action_layout_16pt) mLayoutEM = 16;
			if (oldLayoutEM != mLayoutEM)
				relayoutDocument();
			return true;
		});

		PopupMenu popupMenu = new PopupMenu(
				contextThemeWrapper, mTopBarSwitcher, Gravity.END
		);
		popupMenu.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == R.id.info) {
				showInfoDialog();
			} else if (item.getItemId() == R.id.outline) {
				if (core.hasOutline()) {
					if (mFlatOutline == null)
						mFlatOutline = core.getOutline();
					if (mFlatOutline != null) {
						Intent intent = new Intent(DocumentActivity.this, OutlineActivity.class);
						Bundle bundle = new Bundle();
						bundle.putInt("POSITION", mDocView.getDisplayedViewIndex());
						bundle.putSerializable("OUTLINE", mFlatOutline);
						intent.putExtra("PALLETBUNDLE", Pallet.sendBundle(bundle));
						startActivityForResult(intent, OUTLINE_REQUEST);
					}
				} else {
					Toast.makeText(getBaseContext(),
							getResources().getString(R.string.not_found),
							Toast.LENGTH_LONG).show();
				}
			} else if (item.getItemId() == R.id.highlight_links) {
				setLinkHighlight(!mLinkHighlight);
				item.setChecked(mLinkHighlight);
			} else if (item.getItemId() == R.id.text_size) {
				mLayoutPopupMenu.show();
			}
			return true;
		});
		popupMenu.inflate(R.menu.overflow_menu);
		if (core.isReflowable()) {
			popupMenu.getMenu().findItem(R.id.text_size).setVisible(true);
		}
		mOverflowButton.setOnClickListener(v -> popupMenu.show());
	}

	private void showInfoDialog() {
		String dialogMessage = "";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.title), mDocTitle) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.author), core.getAuthor()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.subject), core.getSubject()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.creator), core.getCreator()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.producer), core.getProducer()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.keywords), core.getKeywords()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.creation_date), core.getCreationDate()) + "\n";
		dialogMessage += String.format(getResources().getString(R.string.info_summary),
				getResources().getString(R.string.modified_date), core.getModifiedDate()) + "\n";
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Custom_Dialog_Dark);
		builder.setMessage(dialogMessage)
				.setTitle(R.string.information)
				.setNegativeButton(getResources().getString(R.string.close),
						(dialog, which) -> dialog.dismiss()).show();
	}

	private void showKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(mSearchText, 0);
	}

	private void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}

	private void search(int direction) {
		hideKeyboard();
		int displayPage = mDocView.getDisplayedViewIndex();
		SearchTaskResult r = SearchTaskResult.get();
		int searchPage = r != null ? r.pageNumber : -1;
		mSearchTask.go(mSearchText.getText().toString(), direction, displayPage, searchPage);
	}

	@Override
	public boolean onSearchRequested() {
		if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOn();
		}
		return super.onSearchRequested();
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOff();
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	protected void onStart() {
		super.onStart();
	}

	@Override
	protected void onStop() {
		super.onStop();
	}

	@Override
	public void onBackPressed() {
		if (mDocView == null || (mDocView != null && !mDocView.popHistory())) {
			super.onBackPressed();
			if (mReturnToLibraryActivity) {
				Intent intent = getPackageManager().getLaunchIntentForPackage(getComponentName().getPackageName());
				startActivity(intent);
			}
		}
	}
}
