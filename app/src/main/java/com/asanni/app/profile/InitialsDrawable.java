package com.asanni.app.profile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.asanni.app.R;
import com.asanni.app.auth.Names;

/**
 * Red circle with the user's initials ("SA"), as in the Settings reference.
 * Used whenever there's no photo (or while it loads). With no name at all, screens use the
 * default ASANNI avatar drawable instead (see avatarFallback()).
 *
 * Location: app/src/main/java/com/asanni/app/profile/InitialsDrawable.java  (NEW in phase 4)
 */
public final class InitialsDrawable extends Drawable {

    private final String initials;
    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public InitialsDrawable(Context c, String username) {
        initials = Names.initials(username);
        circle.setColor(c.getColor(R.color.asanni_avatar_fill));        // [COLOR]
        text.setColor(c.getColor(R.color.asanni_avatar_text));          // [COLOR]
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Initials if there is a name, otherwise the default ASANNI avatar. */
    public static Drawable avatarFallback(Context c, String username) {
        if (Names.initials(username).isEmpty()) {
            // [UI ASSET] Default avatar: res/drawable/asanni_avatar_default.xml
            return c.getDrawable(R.drawable.asanni_avatar_default);
        }
        return new InitialsDrawable(c, username);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle);
        text.setTextSize(r * 0.8f);
        float y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(initials, b.exactCenterX(), y, text);
    }

    @Override public void setAlpha(int alpha) { circle.setAlpha(alpha); text.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { circle.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}