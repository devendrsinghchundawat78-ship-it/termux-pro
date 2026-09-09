package com.termux.pro.app.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * A monospace editor field with a line-number gutter drawn on the left side.
 * Soft-wrapped lines are common on phones; line numbers refer to logical lines.
 */
public class CodeEditText extends AppCompatEditText {

    private final TextPaint mNumberPaint;
    private int mGutterWidth;
    private int mGutterDigits = 1;
    private final Rect mRect = new Rect();
    private Runnable mOnSelectionChanged;

    public CodeEditText(Context context) {
        this(context, null);
    }

    /** Called whenever the cursor/selection moves. */
    public void setOnSelectionChanged(Runnable listener) {
        mOnSelectionChanged = listener;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (mOnSelectionChanged != null) mOnSelectionChanged.run();
    }

    public CodeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        setTypeface(android.graphics.Typeface.MONOSPACE);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        mNumberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mNumberPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        mNumberPaint.setTextSize(getTextSize() * 0.9f);
        mNumberPaint.setColor(0xFF6A737D);

        // Keep the text above the soft keyboard visible while typing.
        setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    }

    /** Recompute the gutter width whenever the number of digits of the line count changes. */
    private void updateGutter() {
        int lines = getLineCount();
        int digits = Math.max(1, String.valueOf(Math.max(1, lines)).length());
        if (digits == mGutterDigits) return;
        mGutterDigits = digits;
        int newWidth = (int) (mNumberPaint.measureText("8") * (digits + 1)) + 12;
        if (newWidth != mGutterWidth) {
            mGutterWidth = newWidth;
            setPadding(mGutterWidth, getPaddingTop(), getPaddingRight(), getPaddingBottom());
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        updateGutter();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGutter();
    }

    @Override
    public void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawLineNumbers(canvas);
    }

    private void drawLineNumbers(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;

        final int height = getHeight() - getTotalPaddingTop() - getTotalPaddingBottom();
        int firstLine = layout.getLineForVertical(getScrollY());
        int lastLine = layout.getLineForVertical(getScrollY() + height);
        lastLine = Math.min(lastLine, layout.getLineCount() - 1);

        final int fontTop = mNumberPaint.getFontMetricsInt().top;
        final int fontBottom = mNumberPaint.getFontMetricsInt().bottom;

        for (int line = firstLine; line <= lastLine; line++) {
            int lineTop = layout.getLineTop(line) - getScrollY() + getTotalPaddingTop();
            int lineBottom = layout.getLineBottom(line) - getScrollY() + getTotalPaddingTop();
            int centerY = (lineTop + lineBottom) / 2;
            // vertical center of the number text
            int baseline = centerY - (fontTop + fontBottom) / 2;

            String number = String.valueOf(line + 1);
            float x = getTotalPaddingLeft() - mNumberPaint.measureText(number) - 8;
            canvas.drawText(number, x, baseline, mNumberPaint);
        }

        // thin separator line after the gutter
        getLocalVisibleRect(mRect);
        canvas.drawLine(getTotalPaddingLeft() - 6, getScrollY() + getTotalPaddingTop(),
            getTotalPaddingLeft() - 6, getScrollY() + getTotalPaddingTop() + height, mNumberPaint);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            // insert spaces instead of moving focus
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (start >= 0 && end >= 0) {
                getText().replace(Math.min(start, end), Math.max(start, end), "    ");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Length of the current text (helper for highlighting guards). */
    public int codeLength() {
        Editable text = getText();
        return text == null ? 0 : text.length();
    }
}
