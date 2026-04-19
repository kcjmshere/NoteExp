/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import java.text.DateFormatSymbols;
import java.util.Calendar;

import net.micode.notes.R;


import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

/**
 * 日期时间选择控件：提供日期(一周范围)、小时、分钟、AM/PM 的滚轮选择。
 */
public class DateTimePicker extends FrameLayout {

    private static final boolean DEFAULT_ENABLE_STATE = true;

    private static final int HOURS_IN_HALF_DAY = 12;
    private static final int HOURS_IN_ALL_DAY = 24;
    private static final int DAYS_IN_ALL_WEEK = 7;
    private static final int DATE_SPINNER_MIN_VAL = 0;
    private static final int DATE_SPINNER_MAX_VAL = DAYS_IN_ALL_WEEK - 1;
    private static final int HOUR_SPINNER_MIN_VAL_24_HOUR_VIEW = 0;
    private static final int HOUR_SPINNER_MAX_VAL_24_HOUR_VIEW = 23;
    private static final int HOUR_SPINNER_MIN_VAL_12_HOUR_VIEW = 1;
    private static final int HOUR_SPINNER_MAX_VAL_12_HOUR_VIEW = 12;
    private static final int MINUT_SPINNER_MIN_VAL = 0;
    private static final int MINUT_SPINNER_MAX_VAL = 59;
    private static final int AMPM_SPINNER_MIN_VAL = 0;
    private static final int AMPM_SPINNER_MAX_VAL = 1;

    private final NumberPicker mDateSpinner;
    private final NumberPicker mHourSpinner;
    private final NumberPicker mMinuteSpinner;
    private final NumberPicker mAmPmSpinner;
    private Calendar mDate;

    private String[] mDateDisplayValues = new String[DAYS_IN_ALL_WEEK];

    private boolean mIsAm;

    private boolean mIs24HourView;

    private boolean mIsEnabled = DEFAULT_ENABLE_STATE;

    private boolean mInitialising;

    private OnDateTimeChangedListener mOnDateTimeChangedListener;

    private NumberPicker.OnValueChangeListener mOnDateChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        // 日期滚轮回调：根据滚轮变化调整 Calendar，并刷新显示/触发回调。
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            mDate.add(Calendar.DAY_OF_YEAR, newVal - oldVal);
            updateDateControl();
            onDateTimeChanged();
        }
    };

    private NumberPicker.OnValueChangeListener mOnHourChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        // 小时滚轮回调：处理跨日与 AM/PM 切换，并同步 Calendar。
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            boolean isDateChanged = false;
            Calendar cal = Calendar.getInstance();
            if (!mIs24HourView) {
                if (!mIsAm && oldVal == HOURS_IN_HALF_DAY - 1 && newVal == HOURS_IN_HALF_DAY) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    isDateChanged = true;
                } else if (mIsAm && oldVal == HOURS_IN_HALF_DAY && newVal == HOURS_IN_HALF_DAY - 1) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    isDateChanged = true;
                }
                if (oldVal == HOURS_IN_HALF_DAY - 1 && newVal == HOURS_IN_HALF_DAY ||
                        oldVal == HOURS_IN_HALF_DAY && newVal == HOURS_IN_HALF_DAY - 1) {
                    mIsAm = !mIsAm;
                    updateAmPmControl();
                }
            } else {
                if (oldVal == HOURS_IN_ALL_DAY - 1 && newVal == 0) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    isDateChanged = true;
                } else if (oldVal == 0 && newVal == HOURS_IN_ALL_DAY - 1) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    isDateChanged = true;
                }
            }
            int newHour = mHourSpinner.getValue() % HOURS_IN_HALF_DAY + (mIsAm ? 0 : HOURS_IN_HALF_DAY);
            mDate.set(Calendar.HOUR_OF_DAY, newHour);
            onDateTimeChanged();
            if (isDateChanged) {
                setCurrentYear(cal.get(Calendar.YEAR));
                setCurrentMonth(cal.get(Calendar.MONTH));
                setCurrentDay(cal.get(Calendar.DAY_OF_MONTH));
            }
        }
    };

    private NumberPicker.OnValueChangeListener mOnMinuteChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        // 分钟滚轮回调：处理分钟进位/借位导致的小时变化，并同步 Calendar。
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            int minValue = mMinuteSpinner.getMinValue();
            int maxValue = mMinuteSpinner.getMaxValue();
            int offset = 0;
            if (oldVal == maxValue && newVal == minValue) {
                offset += 1;
            } else if (oldVal == minValue && newVal == maxValue) {
                offset -= 1;
            }
            if (offset != 0) {
                mDate.add(Calendar.HOUR_OF_DAY, offset);
                mHourSpinner.setValue(getCurrentHour());
                updateDateControl();
                int newHour = getCurrentHourOfDay();
                if (newHour >= HOURS_IN_HALF_DAY) {
                    mIsAm = false;
                    updateAmPmControl();
                } else {
                    mIsAm = true;
                    updateAmPmControl();
                }
            }
            mDate.set(Calendar.MINUTE, newVal);
            onDateTimeChanged();
        }
    };

    private NumberPicker.OnValueChangeListener mOnAmPmChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        // AM/PM 滚轮回调：切换上下午并调整 Calendar 的小时。
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            mIsAm = !mIsAm;
            if (mIsAm) {
                mDate.add(Calendar.HOUR_OF_DAY, -HOURS_IN_HALF_DAY);
            } else {
                mDate.add(Calendar.HOUR_OF_DAY, HOURS_IN_HALF_DAY);
            }
            updateAmPmControl();
            onDateTimeChanged();
        }
    };

    public interface OnDateTimeChangedListener {
        // 日期时间变化回调：当任一滚轮变化后通知外部。
        void onDateTimeChanged(DateTimePicker view, int year, int month,
                int dayOfMonth, int hourOfDay, int minute);
    }

    // 构造：默认以当前时间初始化。
    public DateTimePicker(Context context) {
        this(context, System.currentTimeMillis());
    }

    // 构造：以指定时间戳初始化。
    public DateTimePicker(Context context, long date) {
        this(context, date, DateFormat.is24HourFormat(context));
    }

    // 构造：以指定时间戳与 24 小时制配置初始化并绑定各滚轮。
    public DateTimePicker(Context context, long date, boolean is24HourView) {
        super(context);
        mDate = Calendar.getInstance();
        mInitialising = true;
        mIsAm = getCurrentHourOfDay() >= HOURS_IN_HALF_DAY;
        inflate(context, R.layout.datetime_picker, this);

        mDateSpinner = (NumberPicker) findViewById(R.id.date);
        mDateSpinner.setMinValue(DATE_SPINNER_MIN_VAL);
        mDateSpinner.setMaxValue(DATE_SPINNER_MAX_VAL);
        mDateSpinner.setOnValueChangedListener(mOnDateChangedListener);

        mHourSpinner = (NumberPicker) findViewById(R.id.hour);
        mHourSpinner.setOnValueChangedListener(mOnHourChangedListener);
        mMinuteSpinner =  (NumberPicker) findViewById(R.id.minute);
        mMinuteSpinner.setMinValue(MINUT_SPINNER_MIN_VAL);
        mMinuteSpinner.setMaxValue(MINUT_SPINNER_MAX_VAL);
        mMinuteSpinner.setOnLongPressUpdateInterval(100);
        mMinuteSpinner.setOnValueChangedListener(mOnMinuteChangedListener);

        String[] stringsForAmPm = new DateFormatSymbols().getAmPmStrings();
        mAmPmSpinner = (NumberPicker) findViewById(R.id.amPm);
        mAmPmSpinner.setMinValue(AMPM_SPINNER_MIN_VAL);
        mAmPmSpinner.setMaxValue(AMPM_SPINNER_MAX_VAL);
        mAmPmSpinner.setDisplayedValues(stringsForAmPm);
        mAmPmSpinner.setOnValueChangedListener(mOnAmPmChangedListener);

        // update controls to initial state
        updateDateControl();
        updateHourControl();
        updateAmPmControl();

        set24HourView(is24HourView);

        // set to current time
        setCurrentDate(date);

        setEnabled(isEnabled());

        // set the content descriptions
        mInitialising = false;
    }

    @Override
    // 启用/禁用：同步设置四个滚轮的可用状态。
    public void setEnabled(boolean enabled) {
        if (mIsEnabled == enabled) {
            return;
        }
        super.setEnabled(enabled);
        mDateSpinner.setEnabled(enabled);
        mMinuteSpinner.setEnabled(enabled);
        mHourSpinner.setEnabled(enabled);
        mAmPmSpinner.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    @Override
    // 查询当前控件是否可用。
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * 中文说明：返回当前选择的时间戳（毫秒）。
     * Get the current date in millis
     *
     * @return the current date in millis
     */
    public long getCurrentDateInTimeMillis() {
        return mDate.getTimeInMillis();
    }

    /**
     * 中文说明：用时间戳设置当前选择的日期时间。
     * Set the current date
     *
     * @param date The current date in millis
     */
    public void setCurrentDate(long date) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(date);
        setCurrentDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    /**
        * 中文说明：用年月日时分设置当前选择的日期时间。
     * Set the current date
     *
     * @param year The current year
     * @param month The current month
     * @param dayOfMonth The current dayOfMonth
     * @param hourOfDay The current hourOfDay
     * @param minute The current minute
     */
    public void setCurrentDate(int year, int month,
            int dayOfMonth, int hourOfDay, int minute) {
        setCurrentYear(year);
        setCurrentMonth(month);
        setCurrentDay(dayOfMonth);
        setCurrentHour(hourOfDay);
        setCurrentMinute(minute);
    }

    /**
     * 中文说明：获取当前选择的年份。
     * Get current year
     *
     * @return The current year
     */
    public int getCurrentYear() {
        return mDate.get(Calendar.YEAR);
    }

    /**
     * 中文说明：设置当前选择的年份，并刷新日期滚轮。
     * Set current year
     *
     * @param year The current year
     */
    public void setCurrentYear(int year) {
        if (!mInitialising && year == getCurrentYear()) {
            return;
        }
        mDate.set(Calendar.YEAR, year);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * 中文说明：获取当前选择的月份（0-11）。
     * Get current month in the year
     *
     * @return The current month in the year
     */
    public int getCurrentMonth() {
        return mDate.get(Calendar.MONTH);
    }

    /**
     * 中文说明：设置当前选择的月份（0-11），并刷新日期滚轮。
     * Set current month in the year
     *
     * @param month The month in the year
     */
    public void setCurrentMonth(int month) {
        if (!mInitialising && month == getCurrentMonth()) {
            return;
        }
        mDate.set(Calendar.MONTH, month);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * 中文说明：获取当前选择的“日”（1-31）。
     * Get current day of the month
     *
     * @return The day of the month
     */
    public int getCurrentDay() {
        return mDate.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 中文说明：设置当前选择的“日”（1-31），并刷新日期滚轮。
     * Set current day of the month
     *
     * @param dayOfMonth The day of the month
     */
    public void setCurrentDay(int dayOfMonth) {
        if (!mInitialising && dayOfMonth == getCurrentDay()) {
            return;
        }
        mDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * 中文说明：获取 24 小时制的小时（0-23）。
     * Get current hour in 24 hour mode, in the range (0~23)
     * @return The current hour in 24 hour mode
     */
    public int getCurrentHourOfDay() {
        return mDate.get(Calendar.HOUR_OF_DAY);
    }

    // 获取用于“显示”的小时：根据 24/12 小时制返回对应取值。
    private int getCurrentHour() {
        if (mIs24HourView){
            return getCurrentHourOfDay();
        } else {
            int hour = getCurrentHourOfDay();
            if (hour > HOURS_IN_HALF_DAY) {
                return hour - HOURS_IN_HALF_DAY;
            } else {
                return hour == 0 ? HOURS_IN_HALF_DAY : hour;
            }
        }
    }

    /**
     * 中文说明：设置 24 小时制小时（0-23），并在 12 小时制下同步 AM/PM。
     * Set current hour in 24 hour mode, in the range (0~23)
     *
     * @param hourOfDay
     */
    public void setCurrentHour(int hourOfDay) {
        if (!mInitialising && hourOfDay == getCurrentHourOfDay()) {
            return;
        }
        mDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
        if (!mIs24HourView) {
            if (hourOfDay >= HOURS_IN_HALF_DAY) {
                mIsAm = false;
                if (hourOfDay > HOURS_IN_HALF_DAY) {
                    hourOfDay -= HOURS_IN_HALF_DAY;
                }
            } else {
                mIsAm = true;
                if (hourOfDay == 0) {
                    hourOfDay = HOURS_IN_HALF_DAY;
                }
            }
            updateAmPmControl();
        }
        mHourSpinner.setValue(hourOfDay);
        onDateTimeChanged();
    }

    /**
     * 中文说明：获取当前选择的分钟（0-59）。
     * Get currentMinute
     *
     * @return The Current Minute
     */
    public int getCurrentMinute() {
        return mDate.get(Calendar.MINUTE);
    }

    /**
     * 中文说明：设置当前选择的分钟（0-59）。
     * Set current minute
     */
    public void setCurrentMinute(int minute) {
        if (!mInitialising && minute == getCurrentMinute()) {
            return;
        }
        mMinuteSpinner.setValue(minute);
        mDate.set(Calendar.MINUTE, minute);
        onDateTimeChanged();
    }

    /**
     * 中文说明：查询当前是否为 24 小时制显示。
     * @return true if this is in 24 hour view else false.
     */
    public boolean is24HourView () {
        return mIs24HourView;
    }

    /**
     * 中文说明：切换 24 小时制/AMPM 显示，并刷新滚轮范围与显示。
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True for 24 hour mode. False for AM/PM mode.
     */
    public void set24HourView(boolean is24HourView) {
        if (mIs24HourView == is24HourView) {
            return;
        }
        mIs24HourView = is24HourView;
        mAmPmSpinner.setVisibility(is24HourView ? View.GONE : View.VISIBLE);
        int hour = getCurrentHourOfDay();
        updateHourControl();
        setCurrentHour(hour);
        updateAmPmControl();
    }

    // 刷新日期滚轮：以当前日期为中心生成一周的显示文案。
    private void updateDateControl() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mDate.getTimeInMillis());
        cal.add(Calendar.DAY_OF_YEAR, -DAYS_IN_ALL_WEEK / 2 - 1);
        mDateSpinner.setDisplayedValues(null);
        for (int i = 0; i < DAYS_IN_ALL_WEEK; ++i) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            mDateDisplayValues[i] = (String) DateFormat.format("MM.dd EEEE", cal);
        }
        mDateSpinner.setDisplayedValues(mDateDisplayValues);
        mDateSpinner.setValue(DAYS_IN_ALL_WEEK / 2);
        mDateSpinner.invalidate();
    }

    // 刷新 AM/PM 滚轮显示（仅 12 小时制可见）。
    private void updateAmPmControl() {
        if (mIs24HourView) {
            mAmPmSpinner.setVisibility(View.GONE);
        } else {
            int index = mIsAm ? Calendar.AM : Calendar.PM;
            mAmPmSpinner.setValue(index);
            mAmPmSpinner.setVisibility(View.VISIBLE);
        }
    }

    // 刷新小时滚轮范围：根据 24/12 小时制调整最小/最大值。
    private void updateHourControl() {
        if (mIs24HourView) {
            mHourSpinner.setMinValue(HOUR_SPINNER_MIN_VAL_24_HOUR_VIEW);
            mHourSpinner.setMaxValue(HOUR_SPINNER_MAX_VAL_24_HOUR_VIEW);
        } else {
            mHourSpinner.setMinValue(HOUR_SPINNER_MIN_VAL_12_HOUR_VIEW);
            mHourSpinner.setMaxValue(HOUR_SPINNER_MAX_VAL_12_HOUR_VIEW);
        }
    }

    /**
     * 中文说明：设置日期时间变化监听器。
     * Set the callback that indicates the 'Set' button has been pressed.
     * @param callback the callback, if null will do nothing
     */
    public void setOnDateTimeChangedListener(OnDateTimeChangedListener callback) {
        mOnDateTimeChangedListener = callback;
    }

    // 内部统一入口：任一控件变化后，向外触发 OnDateTimeChangedListener。
    private void onDateTimeChanged() {
        if (mOnDateTimeChangedListener != null) {
            mOnDateTimeChangedListener.onDateTimeChanged(this, getCurrentYear(),
                    getCurrentMonth(), getCurrentDay(), getCurrentHourOfDay(), getCurrentMinute());
        }
    }
}
