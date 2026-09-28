package cn.yuchuang.floatcontrol;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;

/** Shared design tokens for the settings screens and the floating overlay. */
final class Ui {
    static final int BRAND = 0xff0967e9;

    static final int TEXT_CAPTION = 12;
    static final int TEXT_SECONDARY = 13;
    static final int TEXT_BODY = 16;
    static final int TEXT_SUBHEAD = 18;
    static final int TEXT_HEADLINE = 24;
    static final int TEXT_DISPLAY = 30;

    static final int RADIUS_S = 10;
    static final int RADIUS_M = 14;
    static final int RADIUS_L = 22;

    static final int TOUCH_MIN = 48;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.DEFAULT_BOLD;

    static final int STYLE_SYSTEM = 0, STYLE_LIGHT = 1, STYLE_DARK = 2, STYLE_SUNLIGHT = 3, STYLE_GLASS = 4;
    static final String[] OVERLAY_STYLES = {"跟随系统", "浅色", "深色", "强光高对比", "磨砂玻璃"};

    private Ui() {}

    static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int overlayStyle(Context context) {
        int style = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("overlayStyle", STYLE_SYSTEM);
        return Math.max(STYLE_SYSTEM, Math.min(STYLE_GLASS, style));
    }

    static int withAlpha(int color, int percent) {
        int alpha = Math.round(Math.max(0, Math.min(100, percent)) * 2.55f);
        return (alpha << 24) | (color & 0xffffff);
    }

    /** Colors for the settings activity. */
    static final class Palette {
        final boolean night;
        final int canvas, surface, surfaceMuted, ink, muted, section, divider;
        final int brand, brandFill, brandTint, onBrand, chevron, disabled, switchTrack;
        final int success, successTint, warning, warningTint, ripple;

        private Palette(boolean night) {
            this.night = night;
            if (night) {
                canvas = 0xff0f1115;
                surface = 0xff1b1e24;
                surfaceMuted = 0xff262a32;
                ink = 0xffeef0f4;
                muted = 0xffa3a9b5;
                section = 0xff9aa5bd;
                divider = 0xff2b2f37;
                brand = 0xff5b9bff;
                brandFill = BRAND;
                brandTint = 0xff1c2940;
                onBrand = 0xffffffff;
                chevron = 0xff6f7683;
                disabled = 0xff4a4f58;
                switchTrack = 0xff3a3f48;
                success = 0xff6fdc9c;
                successTint = 0xff15301f;
                warning = 0xffffb95c;
                warningTint = 0xff3a2a14;
                ripple = 0x335b9bff;
            } else {
                canvas = 0xfff5f6f8;
                surface = 0xffffffff;
                surfaceMuted = 0xfff1f2f5;
                ink = 0xff17191d;
                muted = 0xff60646e;
                section = 0xff5d6680;
                divider = 0xffeceef2;
                brand = BRAND;
                brandFill = BRAND;
                brandTint = 0xffe8f0fe;
                onBrand = 0xffffffff;
                chevron = 0xff9aa2ad;
                disabled = 0xffc3c7cc;
                switchTrack = 0xffe2e3e5;
                success = 0xff137a45;
                successTint = 0xffe4f5ea;
                warning = 0xff9a5700;
                warningTint = 0xfffff0da;
                ripple = 0x1f0967e9;
            }
        }
    }

    static Palette app(Context context) {
        return new Palette(isNight(context));
    }

    /** Colors for the floating overlay, resolved from the chosen style. */
    static final class Overlay {
        final int style;
        final boolean glass, blurred, dark;
        final int panel, panelStroke, highlight;
        final int button, buttonStroke, ink, accent, onAccent, ripple;
        final int elevationDp;

        private Overlay(int style, boolean glass, boolean blurred, boolean dark, int panel,
                        int panelStroke, int highlight, int button, int buttonStroke, int ink,
                        int accent, int onAccent, int ripple, int elevationDp) {
            this.style = style;
            this.glass = glass;
            this.blurred = blurred;
            this.dark = dark;
            this.panel = panel;
            this.panelStroke = panelStroke;
            this.highlight = highlight;
            this.button = button;
            this.buttonStroke = buttonStroke;
            this.ink = ink;
            this.accent = accent;
            this.onAccent = onAccent;
            this.ripple = ripple;
            this.elevationDp = elevationDp;
        }
    }

    /**
     * @param opacity panel opacity in percent from settings
     * @param blurAvailable whether the system currently allows cross-window blur
     */
    static Overlay overlay(Context context, int style, int opacity, boolean blurAvailable) {
        boolean night = isNight(context);
        switch (style) {
            case STYLE_LIGHT:
                return lightOverlay(opacity);
            case STYLE_DARK:
                return darkOverlay(opacity);
            case STYLE_SUNLIGHT:
                return new Overlay(style, false, false, false, 0xff000000, 0xffffffff, 0,
                    0xffffffff, 0, 0xff000000, 0xff0050c8, 0xffffffff, 0x40000000, 8);
            case STYLE_GLASS: {
                int tint = blurAvailable ? Math.max(12, Math.min(70, opacity)) : 88;
                if (night) {
                    return new Overlay(style, true, blurAvailable, true,
                        withAlpha(0x14161a, tint), 0x40ffffff, 0x1fffffff,
                        0x33ffffff, 0x3dffffff, 0xfff5f7fa, 0xff2f7cf6, 0xffffffff, 0x40ffffff, 0);
                }
                return new Overlay(style, true, blurAvailable, false,
                    withAlpha(0xffffff, tint), 0x99ffffff, 0x40ffffff,
                    0x8cffffff, 0x80ffffff, 0xff15181d, BRAND, 0xffffffff, 0x26000000, 0);
            }
            default:
                return night ? darkOverlay(opacity) : lightOverlay(opacity);
        }
    }

    private static Overlay lightOverlay(int opacity) {
        return new Overlay(STYLE_LIGHT, false, false, false, withAlpha(0x000000, opacity),
            0x66ffffff, 0, 0xf5f8fafb, 0x1a000000, 0xff1b1f24, BRAND, 0xffffffff, 0x33000000, 0);
    }

    private static Overlay darkOverlay(int opacity) {
        return new Overlay(STYLE_DARK, false, false, true, withAlpha(0x000000, Math.max(opacity, 30)),
            0x40ffffff, 0, 0xf2262a31, 0x33ffffff, 0xfff2f4f7, 0xff2f7cf6, 0xffffffff, 0x40ffffff, 0);
    }
}
