package com.forsite.javadprocessor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class StripedProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int progress;

    public StripedProgressView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setProgress(int value) {
        progress = Math.max(0, Math.min(100, value));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float border = 2f * density;
        float right = border + (getWidth() - 2f * border) * progress / 100f;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFFFFF);
        canvas.drawRect(border, border, getWidth() - border, getHeight() - border, paint);
        if (progress > 0) {
            canvas.save(); canvas.clipRect(border, border, right, getHeight() - border);
            paint.setColor(0xFFD53A1F); canvas.drawRect(border, border, right, getHeight() - border, paint);
            paint.setColor(0xFFF29A89);
            float step = 24f * density, stripe = 10f * density, h = getHeight();
            for (float x = -h; x < right + step; x += step) {
                Path p = new Path(); p.moveTo(x, h); p.lineTo(x + stripe, h);
                p.lineTo(x + stripe + h, 0); p.lineTo(x + h, 0); p.close(); canvas.drawPath(p, paint);
            }
            canvas.restore();
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(border); paint.setColor(0xFFD53A1F);
        canvas.drawRect(border / 2, border / 2, getWidth() - border / 2, getHeight() - border / 2, paint);
    }
}
