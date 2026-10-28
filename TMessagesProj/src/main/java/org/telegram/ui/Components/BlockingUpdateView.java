package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.voip.CellFlickerDrawable;

import java.util.Locale;

public class BlockingUpdateView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private TextView textView;
    private TextView titleTextView;
    private ImageView logoView;
    private String currentLogoUrl;
    private int bgColor;
    private static final java.util.HashMap<String, Bitmap> logoCache = new java.util.HashMap<>();

    public static class Parsed {
        public String title;
        public String body;
        public org.json.JSONObject cfg;
    }

    public static Parsed parse(String full) {
        Parsed p = new Parsed();
        String t = full == null ? "" : full.replace("\r", "");
        int ci = t.startsWith("@@") ? 0 : t.indexOf("\n@@");
        if (ci >= 0) {
            String j = t.substring(t.indexOf("@@", ci) + 2).trim();
            try {
                p.cfg = new org.json.JSONObject(j);
            } catch (Exception ignore) {
            }
            t = t.substring(0, ci);
        }
        t = t.trim();
        int nlIdx = t.indexOf('\n');
        if (nlIdx > 0) {
            p.title = t.substring(0, nlIdx).trim();
            p.body = t.substring(nlIdx + 1).trim();
        } else {
            p.body = t;
        }
        return p;
    }

    private static int colorOf(org.json.JSONObject cfg, String key, int def) {
        if (cfg == null) {
            return def;
        }
        try {
            String v = cfg.optString(key, "").trim();
            if (v.length() > 0) {
                return Color.parseColor(v);
            }
        } catch (Exception ignore) {
        }
        return def;
    }

    private void loadLogo(final String url) {
        currentLogoUrl = url;
        Bitmap cached = logoCache.get(url);
        if (cached != null) {
            logoView.setImageBitmap(cached);
            return;
        }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() < 6 * 1024 * 1024) {
                    bos.write(buf, 0, n);
                }
                in.close();
                byte[] data = bos.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / sample > 1024 || o.outHeight / sample > 1024) {
                    sample *= 2;
                }
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                if (bmp != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        logoCache.put(url, bmp);
                        if (url.equals(currentLogoUrl)) {
                            logoView.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Exception ignore) {
            }
        }).start();
    }
    private TextView acceptTextView;
    private FrameLayout radialProgressView;
    private FrameLayout acceptButton;
    private RadialProgress radialProgress;
    private ScrollView scrollView;

    private AnimatorSet progressAnimation;

    private TLRPC.TL_help_appUpdate appUpdate;
    private String fileName;
    private int accountNum;
    private int pressCount;

    public BlockingUpdateView(final Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        int top = Build.VERSION.SDK_INT >= 21 ? (int) (AndroidUtilities.statusBarHeight / AndroidUtilities.density) : 0;

        FrameLayout view = new FrameLayout(context);
        addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(176) + (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0)));

        final ImageView imageView = new ImageView(context);
        logoView = imageView;
        try {
            imageView.setImageResource(context.getApplicationInfo().icon);
        } catch (Exception ignore) {
        }
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.addView(imageView, LayoutHelper.createFrame(96, 96, Gravity.CENTER, 0, top, 0, 0));
        imageView.setOnClickListener(v -> {
            pressCount++;
            if (pressCount >= 10) {
                setVisibility(GONE);
                SharedConfig.pendingAppUpdate = null;
                SharedConfig.saveConfig();
            }
        });

        FrameLayout container = new FrameLayout(context);
        scrollView = new ScrollView(context);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        scrollView.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));
        scrollView.setClipToPadding(false);
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 27, 178 + top, 27, 130));


        scrollView.addView(container);

        titleTextView = new TextView(context);
        titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleTextView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setText(LocaleController.getString(R.string.UpdateTelegram));
        container.addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        textView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 44, 0, 0));


        acceptButton = new FrameLayout(context) {
            CellFlickerDrawable cellFlickerDrawable;

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (cellFlickerDrawable == null) {
                    cellFlickerDrawable = new CellFlickerDrawable();
                    cellFlickerDrawable.drawFrame = false;
                    cellFlickerDrawable.repeatProgress = 2f;
                }
                cellFlickerDrawable.setParentWidth(getMeasuredWidth());
                AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                cellFlickerDrawable.draw(canvas, AndroidUtilities.rectTmp, AndroidUtilities.dp(4), null);
                invalidate();
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int size = MeasureSpec.getSize(widthMeasureSpec);
                if (size > AndroidUtilities.dp(260)) {
                    super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(320), MeasureSpec.EXACTLY), heightMeasureSpec);
                } else {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        };
        acceptButton.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        acceptButton.setBackgroundDrawable(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
        acceptButton.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        addView(acceptButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 46, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 45));
        acceptButton.setOnClickListener(view1 -> {
            if (appUpdate != null && !(appUpdate.document instanceof TLRPC.TL_document) && appUpdate.url != null && appUpdate.url.length() > 0) {
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(appUpdate.url));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                } catch (Exception e) {
                    Browser.openUrl(getContext(), appUpdate.url);
                }
                return;
            }
            if (ApplicationLoader.isStandaloneBuild() || BuildVars.DEBUG_VERSION) {
                if (!ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(getContext())) {
                    return;
                }
                if (appUpdate.document instanceof TLRPC.TL_document) {
                    if (!ApplicationLoader.applicationLoaderInstance.openApkInstall((Activity) getContext(), appUpdate.document)) {
                        FileLoader.getInstance(accountNum).loadFile(appUpdate.document, "update", FileLoader.PRIORITY_HIGH, 1);
                        showProgress(true);
                    }
                } else if (appUpdate.url != null) {
                    Browser.openUrl(getContext(), appUpdate.url);
                }
            } else if (BuildVars.isHuaweiStoreApp()){
                Browser.openUrl(context, BuildVars.HUAWEI_STORE_URL);
            } else {
                Browser.openUrl(context, BuildVars.PLAYSTORE_APP_URL);
            }
        });

        acceptTextView = new TextView(context);
        acceptTextView.setGravity(Gravity.CENTER);
        acceptTextView.setTypeface(AndroidUtilities.bold());
        acceptTextView.setTextColor(0xffffffff);
        acceptTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        acceptButton.addView(acceptTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        radialProgressView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                int width = right - left;
                int height = bottom - top;
                int w = AndroidUtilities.dp(36);
                int l = (width - w) / 2;
                int t = (height - w) / 2;
                radialProgress.setProgressRect(l, t, l + w, t + w);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                radialProgress.draw(canvas);
            }
        };
        radialProgressView.setWillNotDraw(false);
        radialProgressView.setAlpha(0.0f);
        radialProgressView.setScaleX(0.1f);
        radialProgressView.setScaleY(0.1f);
        radialProgressView.setVisibility(View.INVISIBLE);
        radialProgress = new RadialProgress(radialProgressView);
        radialProgress.setBackground(null, true, false);
        radialProgress.setProgressColor(0xffffffff);
        acceptButton.addView(radialProgressView, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == GONE) {
            NotificationCenter.getInstance(accountNum).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(accountNum).removeObserver(this, NotificationCenter.fileLoadFailed);
            NotificationCenter.getInstance(accountNum).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoaded) {
            String location = (String) args[0];
            if (fileName != null && fileName.equals(location)) {
                showProgress(false);
                ApplicationLoader.applicationLoaderInstance.openApkInstall((Activity) getContext(), appUpdate.document);
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String location = (String) args[0];
            if (fileName != null && fileName.equals(location)) {
                showProgress(false);
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            String location = (String) args[0];
            if (fileName != null && fileName.equals(location)) {
                Long loadedSize = (Long) args[1];
                Long totalSize = (Long) args[2];
                float progress = Math.min(1f, loadedSize / (float) totalSize);
                radialProgress.setProgress(progress, true);
            }
        }
    }



    private void showProgress(final boolean show) {
        if (progressAnimation != null) {
            progressAnimation.cancel();
        }
        progressAnimation = new AnimatorSet();
        if (show) {
            radialProgressView.setVisibility(View.VISIBLE);
            acceptButton.setEnabled(false);
            progressAnimation.playTogether(
                    ObjectAnimator.ofFloat(acceptTextView, View.SCALE_X, 0.1f),
                    ObjectAnimator.ofFloat(acceptTextView, View.SCALE_Y, 0.1f),
                    ObjectAnimator.ofFloat(acceptTextView, View.ALPHA, 0.0f),
                    ObjectAnimator.ofFloat(radialProgressView, View.SCALE_X, 1.0f),
                    ObjectAnimator.ofFloat(radialProgressView, View.SCALE_Y, 1.0f),
                    ObjectAnimator.ofFloat(radialProgressView, View.ALPHA, 1.0f));
        } else {
            acceptTextView.setVisibility(View.VISIBLE);
            acceptButton.setEnabled(true);
            progressAnimation.playTogether(
                    ObjectAnimator.ofFloat(radialProgressView, View.SCALE_X, 0.1f),
                    ObjectAnimator.ofFloat(radialProgressView, View.SCALE_Y, 0.1f),
                    ObjectAnimator.ofFloat(radialProgressView, View.ALPHA, 0.0f),
                    ObjectAnimator.ofFloat(acceptTextView, View.SCALE_X, 1.0f),
                    ObjectAnimator.ofFloat(acceptTextView, View.SCALE_Y, 1.0f),
                    ObjectAnimator.ofFloat(acceptTextView, View.ALPHA, 1.0f));

        }
        progressAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (progressAnimation != null && progressAnimation.equals(animation)) {
                    if (!show) {
                        radialProgressView.setVisibility(View.INVISIBLE);
                    } else {
                        acceptTextView.setVisibility(View.INVISIBLE);
                    }
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (progressAnimation != null && progressAnimation.equals(animation)) {
                    progressAnimation = null;
                }
            }
        });
        progressAnimation.setDuration(150);
        progressAnimation.start();
    }

    public void show(int account, TLRPC.TL_help_appUpdate update, boolean check) {
        pressCount = 0;
        appUpdate = update;
        accountNum = account;
        if (update.document instanceof TLRPC.TL_document) {
            fileName = FileLoader.getAttachFileName(update.document);
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        Parsed ps = parse(update.text);
        org.json.JSONObject cfg = ps.cfg;
        int defBg = Theme.getColor(Theme.key_windowBackgroundWhite);
        bgColor = colorOf(cfg, "bg", defBg);
        setBackgroundColor(bgColor);
        gradientDrawableTop.setColors(new int[]{bgColor, Color.TRANSPARENT});
        gradientDrawableBottom.setColors(new int[]{bgColor, Color.TRANSPARENT});
        titleTextView.setTextColor(colorOf(cfg, "title_color", Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)));
        textView.setTextColor(colorOf(cfg, "text_color", Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)));
        titleTextView.setText(ps.title != null && ps.title.length() > 0 ? ps.title : LocaleController.getString(R.string.UpdateTelegram));
        SpannableStringBuilder builder = new SpannableStringBuilder(ps.body);
        if (ps.title == null && cfg == null) {
            MessageObject.addEntitiesToText(builder, update.entities, false, false, false, false);
        }
        textView.setText(builder);

        // logo
        String logoUrl = cfg == null ? "" : cfg.optString("logo", "").trim();
        int logoSize = cfg == null ? 96 : Math.max(48, Math.min(140, cfg.optInt("logo_size", 96)));
        FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) logoView.getLayoutParams();
        llp.width = AndroidUtilities.dp(logoSize);
        llp.height = AndroidUtilities.dp(logoSize);
        logoView.setLayoutParams(llp);
        if (logoUrl.length() > 0) {
            loadLogo(logoUrl);
        } else {
            currentLogoUrl = null;
            try {
                logoView.setImageResource(getContext().getApplicationInfo().icon);
            } catch (Exception ignore) {
            }
        }

        // button
        String btnColorStr = cfg == null ? "" : cfg.optString("btn_color", "").trim();
        if (btnColorStr.length() > 0) {
            int btnColor = colorOf(cfg, "btn_color", 0xff2196f3);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(btnColor);
            gd.setCornerRadius(AndroidUtilities.dp(6));
            acceptButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), gd, null));
        } else {
            acceptButton.setBackgroundDrawable(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
        }
        acceptTextView.setTextColor(colorOf(cfg, "btn_text_color", 0xffffffff));
        acceptButton.setVisibility(cfg != null && cfg.optBoolean("hide_btn", false) ? View.GONE : View.VISIBLE);
        String btnText = cfg == null ? "" : cfg.optString("btn_text", "").trim();
        if (btnText.length() > 0) {
            acceptTextView.setText(btnText);
        } else if (update.document instanceof TLRPC.TL_document) {
            acceptTextView.setText(LocaleController.getString(R.string.Update) + String.format(Locale.US, " (%1$s)", AndroidUtilities.formatFileSize(update.document.size)));
        } else {
            acceptTextView.setText(LocaleController.getString(R.string.Update));
        }
        NotificationCenter.getInstance(accountNum).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(accountNum).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(accountNum).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        if (check && ApplicationLoader.isStandaloneBuild()) {
            TLRPC.TL_help_getAppUpdate req = new TLRPC.TL_help_getAppUpdate();
            try {
                req.source = ApplicationLoader.applicationContext.getPackageManager().getInstallerPackageName(ApplicationLoader.applicationContext.getPackageName());
            } catch (Exception ignore) {

            }
            if (req.source == null) {
                req.source = "";
            }
            ConnectionsManager.getInstance(accountNum).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_help_appUpdate) {
                    final TLRPC.TL_help_appUpdate res = (TLRPC.TL_help_appUpdate) response;
                    if (!res.can_not_skip) {
                        setVisibility(GONE);
                        SharedConfig.pendingAppUpdate = null;
                        SharedConfig.saveConfig();
                    } else if (appUpdate == null || res.id != appUpdate.id) {
                        SharedConfig.pendingAppUpdate = res;
                        SharedConfig.saveConfig();
                        show(accountNum, res, false);
                    }
                } else if (response instanceof TLRPC.TL_help_noAppUpdate) {
                    setVisibility(GONE);
                    SharedConfig.pendingAppUpdate = null;
                    SharedConfig.saveConfig();
                }
            }));
        }
    }

    GradientDrawable gradientDrawableTop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Theme.getColor(Theme.key_windowBackgroundWhite), Color.TRANSPARENT });
    GradientDrawable gradientDrawableBottom = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {Theme.getColor(Theme.key_windowBackgroundWhite), Color.TRANSPARENT });

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        gradientDrawableTop.setBounds(scrollView.getLeft(), scrollView.getTop(), scrollView.getRight(), scrollView.getTop() + AndroidUtilities.dp(16));
        gradientDrawableTop.draw(canvas);

        gradientDrawableBottom.setBounds(scrollView.getLeft(), scrollView.getBottom() - AndroidUtilities.dp(18), scrollView.getRight(), scrollView.getBottom());
        gradientDrawableBottom.draw(canvas);
    }

}
