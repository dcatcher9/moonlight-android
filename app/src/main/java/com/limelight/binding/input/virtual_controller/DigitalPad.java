/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

public class DigitalPad extends VirtualControllerElement {
    public final static int DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0;
    int direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION;
    public final static int DIGITAL_PAD_DIRECTION_LEFT = 1;
    public final static int DIGITAL_PAD_DIRECTION_UP = 2;
    public final static int DIGITAL_PAD_DIRECTION_RIGHT = 4;
    public final static int DIGITAL_PAD_DIRECTION_DOWN = 8;
    List<DigitalPadListener> listeners = new ArrayList<>();

    private static final int DPAD_MARGIN = 5;
    private final RectF rect = new RectF();

    private final Paint paint = new Paint();

    public DigitalPad(VirtualController controller, Context context) {
        super(controller, context, EID_DPAD);
    }

    public void addDigitalPadListener(DigitalPadListener listener) {
        listeners.add(listener);
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        canvas.drawColor(Color.TRANSPARENT);

        paint.setTextSize(getPercent(getCorrectWidth(), 20));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(getDefaultStrokeWidth());
        //虚拟手柄皮肤 yuzu
        int oscOpacity = 90;

        paint.setColor(isPressed() ? pressedColor:getDefaultColor());
        rect.left = rect.top = paint.getStrokeWidth();
        rect.right = getWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;

        boolean bIsMoving = virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons;
        boolean bIsResizing = virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons;
        boolean bIsEnable = virtualController.getControllerMode() == VirtualController.ControllerMode.DisableEnableButtons;

        if (bIsMoving || bIsResizing || bIsEnable) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(rect,paint);
        }

        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.setAlpha((int) (oscOpacity*2.55));
            d.draw(canvas);
        }

        if (direction == DIGITAL_PAD_DIRECTION_UP) {
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.setAlpha((int) (oscOpacity*2.55));
            d.draw(canvas);
        }

        if (direction == DIGITAL_PAD_DIRECTION_DOWN) {
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up);
            Drawable newD=rotateDrawable(d,180);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }

        if (direction == DIGITAL_PAD_DIRECTION_LEFT) {
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up);
            Drawable newD=rotateDrawable(d,270);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }

        if (direction == DIGITAL_PAD_DIRECTION_RIGHT) {
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up);
            Drawable newD=rotateDrawable(d,90);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }
        //right up
        if((direction & DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction & DIGITAL_PAD_DIRECTION_UP) > 0){
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up_right);
            Drawable newD=rotateDrawable(d,90);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }

        if((direction & DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction & DIGITAL_PAD_DIRECTION_UP) > 0){
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up_right);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.setAlpha((int) (oscOpacity*2.55));
            d.draw(canvas);
        }

        if((direction & DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction & DIGITAL_PAD_DIRECTION_DOWN) > 0){
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up_right);
            Drawable newD=rotateDrawable(d,180);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }

        if((direction & DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction & DIGITAL_PAD_DIRECTION_DOWN) > 0){
            Drawable d = getResources().getDrawable(R.drawable.facebutton_dpad_up_right);
            Drawable newD=rotateDrawable(d,270);
            newD.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            newD.setAlpha((int) (oscOpacity*2.55));
            newD.draw(canvas);
        }
    }

    public Drawable rotateDrawable(Drawable vectorDrawable, float angle) {
        int width = vectorDrawable.getIntrinsicWidth();
        int height = vectorDrawable.getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);

        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        return new BitmapDrawable(getResources(), rotatedBitmap);
    }

    private void newDirectionCallback(int direction) {
        _DBG("direction: " + direction);

        // notify listeners
        for (DigitalPadListener listener : listeners) {
            listener.onDirectionChange(direction);
        }
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                direction = 0;

                if (event.getX() < getPercent(getWidth(), 33)) {
                    direction |= DIGITAL_PAD_DIRECTION_LEFT;
                }
                if (event.getX() > getPercent(getWidth(), 66)) {
                    direction |= DIGITAL_PAD_DIRECTION_RIGHT;
                }
                if (event.getY() > getPercent(getHeight(), 66)) {
                    direction |= DIGITAL_PAD_DIRECTION_DOWN;
                }
                if (event.getY() < getPercent(getHeight(), 33)) {
                    direction |= DIGITAL_PAD_DIRECTION_UP;
                }
                newDirectionCallback(direction);
                invalidate();

                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                direction = 0;
                newDirectionCallback(direction);
                invalidate();

                return true;
            }
            default: {
            }
        }

        return true;
    }

    public interface DigitalPadListener {
        void onDirectionChange(int direction);
    }
}
