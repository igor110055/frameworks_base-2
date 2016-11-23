/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.text.NumberFormat;

public class StatusBarIconView extends AnimatedImageView {
    public static final int STATE_ICON = 0;
    public static final int STATE_DOT = 1;
    public static final int STATE_HIDDEN = 2;

    private static final String TAG = "StatusBarIconView";
    private static final Property<StatusBarIconView, Float> ICON_APPEAR_AMOUNT
            = new FloatProperty<StatusBarIconView>("iconAppearAmount") {

        @Override
        public void setValue(StatusBarIconView object, float value) {
            object.setIconAppearAmount(value);
        }

        @Override
        public Float get(StatusBarIconView object) {
            return object.getIconAppearAmount();
        }
    };
    private static final Property<StatusBarIconView, Float> DOT_APPEAR_AMOUNT
            = new FloatProperty<StatusBarIconView>("dot_appear_amount") {

        @Override
        public void setValue(StatusBarIconView object, float value) {
            object.setDotAppearAmount(value);
        }

        @Override
        public Float get(StatusBarIconView object) {
            return object.getDotAppearAmount();
        }
    };

    private boolean mAlwaysScaleIcon;
    private StatusBarIcon mIcon;
    @ViewDebug.ExportedProperty private String mSlot;
    private Drawable mNumberBackground;
    private Paint mNumberPain;
    private int mNumberX;
    private int mNumberY;
    private String mNumberText;
    private Notification mNotification;
    private final boolean mBlocked;
    private int mDensity;
    private float mIconScale = 1.0f;
    private final Paint mDotPaint = new Paint();
    private boolean mDotVisible;
    private float mDotRadius;
    private int mStaticDotRadius;
    private int mVisibleState = STATE_ICON;
    private float mIconAppearAmount = 1.0f;
    private ObjectAnimator mIconAppearAnimator;
    private ObjectAnimator mDotAnimator;
    private float mDotAppearAmount;
    private OnVisibilityChangedListener mOnVisibilityChangedListener;

    public StatusBarIconView(Context context, String slot, Notification notification) {
        this(context, slot, notification, false);
    }

    public StatusBarIconView(Context context, String slot, Notification notification,
            boolean blocked) {
        super(context);
        mBlocked = blocked;
        mSlot = slot;
        mNumberPain = new Paint();
        mNumberPain.setTextAlign(Paint.Align.CENTER);
        mNumberPain.setColor(context.getColor(R.drawable.notification_number_text_color));
        mNumberPain.setAntiAlias(true);
        setNotification(notification);
        maybeUpdateIconScale();
        setScaleType(ScaleType.CENTER);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        if (mNotification != null) {
            setIconTint(getContext().getColor(
                    com.android.internal.R.color.notification_icon_default_color));
        }
        reloadDimens();
    }

    private void maybeUpdateIconScale() {
        // We do not resize and scale system icons (on the right), only notification icons (on the
        // left).
        if (mNotification != null || mAlwaysScaleIcon) {
            updateIconScale();
        }
    }

    private void updateIconScale() {
        Resources res = mContext.getResources();
        final int outerBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_size);
        final int imageBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size);
        mIconScale = (float)imageBounds / (float)outerBounds;
    }

    public float getIconScale() {
        return mIconScale;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int density = newConfig.densityDpi;
        if (density != mDensity) {
            mDensity = density;
            maybeUpdateIconScale();
            updateDrawable();
            reloadDimens();
        }
    }

    private void reloadDimens() {
        boolean applyRadius = mDotRadius == mStaticDotRadius;
        mStaticDotRadius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
        if (applyRadius) {
            mDotRadius = mStaticDotRadius;
        }
    }

    public void setNotification(Notification notification) {
        mNotification = notification;
        setContentDescription(notification);
    }

    public StatusBarIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBlocked = false;
        mAlwaysScaleIcon = true;
        updateIconScale();
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
    }

    private static boolean streq(String a, String b) {
        if (a == b) {
            return true;
        }
        if (a == null && b != null) {
            return false;
        }
        if (a != null && b == null) {
            return false;
        }
        return a.equals(b);
    }

    public boolean equalIcons(Icon a, Icon b) {
        if (a == b) return true;
        if (a.getType() != b.getType()) return false;
        switch (a.getType()) {
            case Icon.TYPE_RESOURCE:
                return a.getResPackage().equals(b.getResPackage()) && a.getResId() == b.getResId();
            case Icon.TYPE_URI:
                return a.getUriString().equals(b.getUriString());
            default:
                return false;
        }
    }
    /**
     * Returns whether the set succeeded.
     */
    public boolean set(StatusBarIcon icon) {
        final boolean iconEquals = mIcon != null && equalIcons(mIcon.icon, icon.icon);
        final boolean levelEquals = iconEquals
                && mIcon.iconLevel == icon.iconLevel;
        final boolean visibilityEquals = mIcon != null
                && mIcon.visible == icon.visible;
        final boolean numberEquals = mIcon != null
                && mIcon.number == icon.number;
        mIcon = icon.clone();
        setContentDescription(icon.contentDescription);
        if (!iconEquals) {
            if (!updateDrawable(false /* no clear */)) return false;
        }
        if (!levelEquals) {
            setImageLevel(icon.iconLevel);
        }

        if (!numberEquals) {
            if (icon.number > 0 && getContext().getResources().getBoolean(
                        R.bool.config_statusBarShowNumber)) {
                if (mNumberBackground == null) {
                    mNumberBackground = getContext().getResources().getDrawable(
                            R.drawable.ic_notification_overlay);
                }
                placeNumber();
            } else {
                mNumberBackground = null;
                mNumberText = null;
            }
            invalidate();
        }
        if (!visibilityEquals) {
            setVisibility(icon.visible && !mBlocked ? VISIBLE : GONE);
        }
        return true;
    }

    public void updateDrawable() {
        updateDrawable(true /* with clear */);
    }

    private boolean updateDrawable(boolean withClear) {
        if (mIcon == null) {
            return false;
        }
        Drawable drawable = getIcon(mIcon);
        if (drawable == null) {
            Log.w(TAG, "No icon for slot " + mSlot);
            return false;
        }
        if (withClear) {
            setImageDrawable(null);
        }
        setImageDrawable(drawable);
        return true;
    }

    private Drawable getIcon(StatusBarIcon icon) {
        return getIcon(getContext(), icon);
    }

    /**
     * Returns the right icon to use for this item
     *
     * @param context Context to use to get resources
     * @return Drawable for this item, or null if the package or item could not
     *         be found
     */
    public static Drawable getIcon(Context context, StatusBarIcon statusBarIcon) {
        int userId = statusBarIcon.user.getIdentifier();
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
        }

        Drawable icon = statusBarIcon.icon.loadDrawableAsUser(context, userId);

        TypedValue typedValue = new TypedValue();
        context.getResources().getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float scaleFactor = typedValue.getFloat();

        // No need to scale the icon, so return it as is.
        if (scaleFactor == 1.f) {
            return icon;
        }

        return new ScalingDrawableWrapper(icon, scaleFactor);
    }

    public StatusBarIcon getStatusBarIcon() {
        return mIcon;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mNotification != null) {
            event.setParcelableData(mNotification);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mNumberBackground != null) {
            placeNumber();
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateDrawable();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mIconAppearAmount > 0.0f) {
            canvas.save();
            canvas.scale(mIconScale * mIconAppearAmount, mIconScale * mIconAppearAmount,
                    getWidth() / 2, getHeight() / 2);
            super.onDraw(canvas);
            canvas.restore();
        }

        if (mNumberBackground != null) {
            mNumberBackground.draw(canvas);
            canvas.drawText(mNumberText, mNumberX, mNumberY, mNumberPain);
        }
        if (mDotAppearAmount != 0.0f) {
            float radius;
            float alpha;
            if (mDotAppearAmount <= 1.0f) {
                radius = mDotRadius * mDotAppearAmount;
                alpha = 1.0f;
            } else {
                float fadeOutAmount = mDotAppearAmount - 1.0f;
                alpha = 1.0f - fadeOutAmount;
                radius = NotificationUtils.interpolate(mDotRadius, getWidth() / 4, fadeOutAmount);
            }
            mDotPaint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius, mDotPaint);
        }
    }

    @Override
    protected void debug(int depth) {
        super.debug(depth);
        Log.d("View", debugIndent(depth) + "slot=" + mSlot);
        Log.d("View", debugIndent(depth) + "icon=" + mIcon);
    }

    void placeNumber() {
        final String str;
        final int tooBig = getContext().getResources().getInteger(
                android.R.integer.status_bar_notification_info_maxnum);
        if (mIcon.number > tooBig) {
            str = getContext().getResources().getString(
                        android.R.string.status_bar_notification_info_overflow);
        } else {
            NumberFormat f = NumberFormat.getIntegerInstance();
            str = f.format(mIcon.number);
        }
        mNumberText = str;

        final int w = getWidth();
        final int h = getHeight();
        final Rect r = new Rect();
        mNumberPain.getTextBounds(str, 0, str.length(), r);
        final int tw = r.right - r.left;
        final int th = r.bottom - r.top;
        mNumberBackground.getPadding(r);
        int dw = r.left + tw + r.right;
        if (dw < mNumberBackground.getMinimumWidth()) {
            dw = mNumberBackground.getMinimumWidth();
        }
        mNumberX = w-r.right-((dw-r.right-r.left)/2);
        int dh = r.top + th + r.bottom;
        if (dh < mNumberBackground.getMinimumWidth()) {
            dh = mNumberBackground.getMinimumWidth();
        }
        mNumberY = h-r.bottom-((dh-r.top-th-r.bottom)/2);
        mNumberBackground.setBounds(w-dw, h-dh, w, h);
    }

    private void setContentDescription(Notification notification) {
        if (notification != null) {
            String d = contentDescForNotification(mContext, notification);
            if (!TextUtils.isEmpty(d)) {
                setContentDescription(d);
            }
        }
    }

    public String toString() {
        return "StatusBarIconView(slot=" + mSlot + " icon=" + mIcon
            + " notification=" + mNotification + ")";
    }

    public String getSlot() {
        return mSlot;
    }


    public static String contentDescForNotification(Context c, Notification n) {
        String appName = "";
        try {
            Notification.Builder builder = Notification.Builder.recoverBuilder(c, n);
            appName = builder.loadHeaderAppName();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to recover builder", e);
            // Trying to get the app name from the app info instead.
            Parcelable appInfo = n.extras.getParcelable(
                    Notification.EXTRA_BUILDER_APPLICATION_INFO);
            if (appInfo instanceof ApplicationInfo) {
                appName = String.valueOf(((ApplicationInfo) appInfo).loadLabel(
                        c.getPackageManager()));
            }
        }

        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence ticker = n.tickerText;

        CharSequence desc = !TextUtils.isEmpty(ticker) ? ticker
                : !TextUtils.isEmpty(title) ? title : "";

        return c.getString(R.string.accessibility_desc_notification_icon, appName, desc);
    }

    public void setIconTint(int iconTint) {
        mDotPaint.setColor(iconTint);
    }

    public void setVisibleState(int state) {
        setVisibleState(state, true /* animate */, null /* endRunnable */);
    }

    public void setVisibleState(int state, boolean animate) {
        setVisibleState(state, animate, null);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setVisibleState(int visibleState, boolean animate, Runnable endRunnable) {
        if (visibleState != mVisibleState) {
            mVisibleState = visibleState;
            if (animate) {
                if (mIconAppearAnimator != null) {
                    mIconAppearAnimator.cancel();
                }
                float targetAmount = 0.0f;
                Interpolator interpolator = Interpolators.FAST_OUT_LINEAR_IN;
                if (visibleState == STATE_ICON) {
                    targetAmount = 1.0f;
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN;
                }
                mIconAppearAnimator = ObjectAnimator.ofFloat(this, ICON_APPEAR_AMOUNT,
                        targetAmount);
                mIconAppearAnimator.setInterpolator(interpolator);
                mIconAppearAnimator.setDuration(100);
                mIconAppearAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mIconAppearAnimator = null;
                        if (endRunnable != null) {
                            endRunnable.run();
                        }
                    }
                });
                mIconAppearAnimator.start();

                if (mDotAnimator != null) {
                    mDotAnimator.cancel();
                }
                targetAmount = visibleState == STATE_ICON ? 2.0f : 0.0f;
                interpolator = Interpolators.FAST_OUT_LINEAR_IN;
                if (visibleState == STATE_DOT) {
                    targetAmount = 1.0f;
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN;
                }
                mDotAnimator = ObjectAnimator.ofFloat(this, DOT_APPEAR_AMOUNT,
                        targetAmount);
                mDotAnimator.setInterpolator(interpolator);
                mDotAnimator.setDuration(100);
                mDotAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mDotAnimator = null;
                    }
                });
                mDotAnimator.start();
            } else {
                setIconAppearAmount(visibleState == STATE_ICON ? 1.0f : 0.0f);
                setDotAppearAmount(visibleState == STATE_DOT ? 1.0f : 0.0f);
            }
        }
    }

    public void setIconAppearAmount(float iconAppearAmount) {
        mIconAppearAmount = iconAppearAmount;
        invalidate();
    }

    public float getIconAppearAmount() {
        return mIconAppearAmount;
    }

    public int getVisibleState() {
        return mVisibleState;
    }

    public void setDotAppearAmount(float dotAppearAmount) {
        mDotAppearAmount = dotAppearAmount;
        invalidate();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (mOnVisibilityChangedListener != null) {
            mOnVisibilityChangedListener.onVisibilityChanged(visibility);
        }
    }

    public float getDotAppearAmount() {
        return mDotAppearAmount;
    }

    public void setOnVisibilityChangedListener(OnVisibilityChangedListener listener) {
        mOnVisibilityChangedListener = listener;
    }

    public interface OnVisibilityChangedListener {
        void onVisibilityChanged(int newVisibility);
    }
}
