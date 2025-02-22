package sh.siava.AOSPMods.systemui;

import static android.service.quicksettings.Tile.STATE_ACTIVE;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.AOSPMods.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.AOSPMods.AOSPMods;
import sh.siava.AOSPMods.XposedModPack;
import sh.siava.AOSPMods.utils.SystemUtils;

@SuppressWarnings("RedundantThrows")
public class FlashLightLevel extends XposedModPack {
    public static final String listenPackage = AOSPMods.SYSTEM_UI_PACKAGE;
    private static boolean leveledFlashTile = false;
    private float currentPct = .5f;
    private static boolean lightQSHeaderEnabled = false;
    Drawable flashPercentageDrawable = null;

    public FlashLightLevel(Context context) {
        super(context);
    }

    @Override
    public void updatePrefs(String... Key) {
        leveledFlashTile = Xprefs.getBoolean("leveledFlashTile", false);
        lightQSHeaderEnabled = Xprefs.getBoolean("LightQSPanel", false);
    }

    @Override
    public boolean listensTo(String packageName) { return listenPackage.equals(packageName); }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if(!lpparam.packageName.equals(listenPackage) || Build.VERSION.SDK_INT < 33) return; //Only SDK 33 and above

        flashPercentageDrawable = new flashPercentageShape();
        flashPercentageDrawable.setAlpha(64);

        Class<?> QSTileViewImplClass = findClass("com.android.systemui.qs.tileimpl.QSTileViewImpl", lpparam.classLoader);

        hookAllMethods(QSTileViewImplClass, "handleStateChanged", new XC_MethodHook() {
            @SuppressLint("DiscouragedApi")
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if(!SystemUtils.supportsFlashLevels() || !leveledFlashTile) return;

                Object state = param.args[0];
                if(getObjectField(state, "spec").equals("flashlight"))
                {
                    Resources res = mContext.getResources();
                    currentPct = Xprefs.getFloat("flashPCT", 0.5f);

                    setObjectField(state, "label",
                            String.format("%s - %s%%",
                                    res.getText(
                                            res.getIdentifier(
                                                    "quick_settings_flashlight_label",
                                                    "string", mContext.getPackageName())),
                                    Math.round(currentPct *100f)
                            )
                    );
                    View thisView = (View) param.thisObject;

                    thisView.setOnTouchListener(new View.OnTouchListener() {
                        float initX = 0;
                        float initPct = 0;
                        boolean moved = false;

                        @SuppressLint({"DiscouragedApi", "ClickableViewAccessibility"})
                        @Override
                        public boolean onTouch(View view, MotionEvent motionEvent) {
                            if(!SystemUtils.supportsFlashLevels() || !leveledFlashTile)
                                return false;

                            switch(motionEvent.getAction())
                            {
                                case MotionEvent.ACTION_DOWN:{
                                    initX = motionEvent.getX();
                                    initPct = initX / view.getWidth();
                                    return true;
                                }
                                case MotionEvent.ACTION_MOVE:{
                                    float newPct = motionEvent.getX() / view.getWidth();
                                    float deltaPct = Math.abs(newPct - initPct);
                                    if(deltaPct > .03f)
                                    {
                                        view.getParent().requestDisallowInterceptTouchEvent(true);
                                        moved = true;
                                        currentPct = Math.max(0.01f, Math.min(newPct, 1));
                                        handleFlashLightClick(false, currentPct);
                                        TextView label = (TextView) getObjectField(thisView, "label");
                                        label.setText(
                                                String.format("%s - %s%%",
                                                        res.getText(
                                                                res.getIdentifier(
                                                                        "quick_settings_flashlight_label",
                                                                        "string", mContext.getPackageName())),
                                                        Math.round(currentPct *100f)
                                                )
                                        );
                                    }
                                    return true;
                                }
                                case MotionEvent.ACTION_UP:{
                                    if (moved) {
                                        moved = false;
                                        Xprefs.edit().putFloat("flashPCT", currentPct).apply();
                                    }
                                    else
                                    {
                                        handleFlashLightClick(true, currentPct);
                                    }
                                    return true;
                                }
                            }
                            return true;
                        }
                    });
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param)
            {
                if(!leveledFlashTile || !SystemUtils.supportsFlashLevels()) return;

                Object state = param.args[0];
                if(getObjectField(state, "spec").equals("flashlight"))
                {
                    LinearLayout tileView = (LinearLayout) param.thisObject;

                    flashPercentageDrawable.setTint(
                            (SystemUtils.isDarkMode() || !lightQSHeaderEnabled) && !getObjectField(state, "state").equals(STATE_ACTIVE)
                                ? Color.WHITE
                                : Color.BLACK);

                    LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{(Drawable) getObjectField(tileView, "colorBackgroundDrawable"), flashPercentageDrawable});
                    tileView.setBackground(layerDrawable);
                }
            }
        });
    }

    private void handleFlashLightClick(boolean toggle, float pct) {
        boolean currState = SystemUtils.isFlashOn();

        if(!toggle && !currState) return; //nothing to do

        if(toggle)
        {
            currState = !currState;
        }

        if(currState)
        {
            SystemUtils.setFlash(true, pct);
        }
        else
        {
            SystemUtils.setFlash(false);
        }
    }

    private class flashPercentageShape extends Drawable
    {
        final Drawable shape;

        @SuppressLint("UseCompatLoadingForDrawables")
        private flashPercentageShape()
        {
            shape = mContext.getDrawable(mContext.getResources().getIdentifier("qs_tile_background_shape", "drawable", mContext.getPackageName()));
        }

        @Override
        public void setBounds(Rect bounds)
        {
            shape.setBounds(bounds);
        }

        @Override
        public void setBounds(int a, int b, int c, int d)
        {
            shape.setBounds(a,b,c,d);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Bitmap bitmap = Bitmap.createBitmap(Math.round(shape.getBounds().width() * currentPct), shape.getBounds().height(), Bitmap.Config.ARGB_8888);
            Canvas tempCanvas = new Canvas(bitmap);
            shape.draw(tempCanvas);

            canvas.drawBitmap(bitmap, 0, 0, new Paint());
        }

        @Override
        public void setAlpha(int i) {
            shape.setAlpha(i);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            shape.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.UNKNOWN;
        }

        @Override
        public void setTint(int t)
        {
            shape.setTint(t);
        }
    }
}