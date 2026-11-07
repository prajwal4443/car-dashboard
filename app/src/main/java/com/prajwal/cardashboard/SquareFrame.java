package com.prajwal.cardashboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/**
 * The square in the map area picker: as large as fits the space it is given, always square, centred by its parent.
 * Draws nothing itself (its background is the frame) and lets touches through to the map below.
 */
public class SquareFrame extends View {
    public SquareFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int side = Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        int exactly = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
        super.onMeasure(exactly, exactly);
    }
}
