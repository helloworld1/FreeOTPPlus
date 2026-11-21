package com.amulyakhare.textdrawable;

import android.graphics.*;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.drawable.shapes.RoundRectShape;

import java.util.Locale;

/**
 * @author amulya
 * @datetime 14 Oct 2014, 3:53 PM
 */
public class TextDrawable extends ShapeDrawable {

    private final Paint textPaint;
    private final Paint borderPaint;
    private static final float SHADE_FACTOR = 0.9f;
    private final String text;
    private final int color;
    private final RectShape shape;
    private final int height;
    private final int width;
    private final int fontSize;
    private final float radius;
    private final float radiusFraction;
    private final int borderThickness;
    private final RectF drawRectF = new RectF();
    private int currentAlpha = 255;

    private TextDrawable(Builder builder) {
        super(builder.shape);

        // shape properties
        shape = builder.shape;
        height = builder.height;
        width = builder.width;
        radius = builder.radius;
        radiusFraction = builder.radiusFraction;

        // text and color
        text = builder.toUpperCase ? builder.text.toUpperCase(Locale.getDefault()) : builder.text;
        color = builder.color;

        // text paint settings
        fontSize = builder.fontSize;
        textPaint = new Paint();
        textPaint.setColor(builder.textColor);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(builder.isBold);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(builder.font);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setStrokeWidth(builder.borderThickness);

        // border paint settings
        borderThickness = builder.borderThickness;
        borderPaint = new Paint();
        borderPaint.setColor(getDarkerShade(color));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderThickness);

        // drawable paint color
        Paint paint = getPaint();
        paint.setAntiAlias(true);
        paint.setColor(color);

    }

    private int getDarkerShade(int color) {
        return Color.rgb((int)(SHADE_FACTOR * Color.red(color)),
                (int)(SHADE_FACTOR * Color.green(color)),
                (int)(SHADE_FACTOR * Color.blue(color)));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect r = getBounds();
        if (radiusFraction > 0f) {
            Paint bgPaint = getPaint();
            int prevAlpha = bgPaint.getAlpha();
            bgPaint.setAlpha((prevAlpha * currentAlpha) / 255);
            drawRectF.set(r);
            float effectiveRadius = Math.min(r.width(), r.height()) * radiusFraction;
            canvas.drawRoundRect(drawRectF, effectiveRadius, effectiveRadius, bgPaint);
            bgPaint.setAlpha(prevAlpha);
        } else {
            super.draw(canvas);
        }

        // draw border
        if (borderThickness > 0) {
            drawBorder(canvas);
        }

        int count = canvas.save();
        canvas.translate(r.left, r.top);

        // draw text
        int width = this.width < 0 ? r.width() : this.width;
        int height = this.height < 0 ? r.height() : this.height;
        int fontSize = this.fontSize < 0 ? (Math.min(width, height) / 2) : this.fontSize;
        textPaint.setTextSize(fontSize);
        canvas.drawText(text, width / 2f, height / 2f - ((textPaint.descent() + textPaint.ascent()) / 2f), textPaint);

        canvas.restoreToCount(count);

    }

    private void drawBorder(Canvas canvas) {
        RectF rect = new RectF(getBounds());
        rect.inset(borderThickness / 2f, borderThickness / 2f);

        if (radiusFraction > 0f) {
            float effectiveRadius = Math.min(rect.width(), rect.height()) * radiusFraction;
            canvas.drawRoundRect(rect, effectiveRadius, effectiveRadius, borderPaint);
        }
        else if (shape instanceof OvalShape) {
            canvas.drawOval(rect, borderPaint);
        }
        else if (shape instanceof RoundRectShape) {
            canvas.drawRoundRect(rect, radius, radius, borderPaint);
        }
        else {
            canvas.drawRect(rect, borderPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        currentAlpha = alpha;
        super.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        super.setColorFilter(cf);
        textPaint.setColorFilter(cf);
        borderPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    public static IShapeBuilder builder() {
        return new Builder();
    }

    public static class Builder implements IConfigBuilder, IShapeBuilder, IBuilder {

        private String text;

        private int color;

        private int borderThickness;

        private int width;

        private int height;

        private Typeface font;

        private RectShape shape;

        public int textColor;

        private int fontSize;

        private boolean isBold;

        private boolean toUpperCase;

        public float radius;

        public float radiusFraction;

        private Builder() {
            text = "";
            color = Color.GRAY;
            textColor = Color.WHITE;
            borderThickness = 0;
            width = -1;
            height = -1;
            shape = new RectShape();
            font = Typeface.create("sans-serif-light", Typeface.NORMAL);
            fontSize = -1;
            isBold = false;
            toUpperCase = false;
            radiusFraction = 0f;
        }

        public IConfigBuilder width(int width) {
            this.width = width;
            return this;
        }

        public IConfigBuilder height(int height) {
            this.height = height;
            return this;
        }

        public IConfigBuilder textColor(int color) {
            this.textColor = color;
            return this;
        }

        public IConfigBuilder withBorder(int thickness) {
            this.borderThickness = thickness;
            return this;
        }

        public IConfigBuilder useFont(Typeface font) {
            this.font = font;
            return this;
        }

        public IConfigBuilder fontSize(int size) {
            this.fontSize = size;
            return this;
        }

        public IConfigBuilder bold() {
            this.isBold = true;
            return this;
        }

        public IConfigBuilder toUpperCase() {
            this.toUpperCase = true;
            return this;
        }

        @Override
        public IConfigBuilder beginConfig() {
            return this;
        }

        @Override
        public IShapeBuilder endConfig() {
            return this;
        }

        @Override
        public IBuilder rect() {
            this.shape = new RectShape();
            return this;
        }

        @Override
        public IBuilder round() {
            this.shape = new OvalShape();
            return this;
        }

        @Override
        public IBuilder roundRect(int radius) {
            this.radius = radius;
            this.radiusFraction = 0f;
            float[] radii = {radius, radius, radius, radius, radius, radius, radius, radius};
            this.shape = new RoundRectShape(radii, null, null);
            return this;
        }

        @Override
        public IBuilder roundRectProportional(float radiusFraction) {
            this.radiusFraction = Math.max(0f, Math.min(0.5f, radiusFraction));
            this.shape = new RectShape();
            return this;
        }

        @Override
        public TextDrawable buildRect(String text, int color) {
            rect();
            return build(text, color);
        }

        @Override
        public TextDrawable buildRoundRect(String text, int color, int radius) {
            roundRect(radius);
            return build(text, color);
        }

        @Override
        public TextDrawable buildRoundRectProportional(String text, int color, float radiusFraction) {
            roundRectProportional(radiusFraction);
            return build(text, color);
        }

        @Override
        public TextDrawable buildRound(String text, int color) {
            round();
            return build(text, color);
        }

        @Override
        public TextDrawable build(String text, int color) {
            this.color = color;
            this.text = text;
            return new TextDrawable(this);
        }
    }

    public interface IConfigBuilder {
        public IConfigBuilder width(int width);

        public IConfigBuilder height(int height);

        public IConfigBuilder textColor(int color);

        public IConfigBuilder withBorder(int thickness);

        public IConfigBuilder useFont(Typeface font);

        public IConfigBuilder fontSize(int size);

        public IConfigBuilder bold();

        public IConfigBuilder toUpperCase();

        public IShapeBuilder endConfig();
    }

    public static interface IBuilder {

        public TextDrawable build(String text, int color);
    }

    public static interface IShapeBuilder {

        public IConfigBuilder beginConfig();

        public IBuilder rect();

        public IBuilder round();

        public IBuilder roundRect(int radius);

        public IBuilder roundRectProportional(float radiusFraction);

        public TextDrawable buildRect(String text, int color);

        public TextDrawable buildRoundRect(String text, int color, int radius);

        public TextDrawable buildRoundRectProportional(String text, int color, float radiusFraction);

        public TextDrawable buildRound(String text, int color);
    }
}