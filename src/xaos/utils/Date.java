package xaos.utils;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public final class Date implements Externalizable {

    private static final long serialVersionUID = -7460035278067931338L;

    public static final int DAYS_PER_MONTH = 30;
    public static final int MONTHS_PER_YEAR = 12;

    private String sDate;

    private int day;
    private int month;
    private int year;

    public Date() {
        setDay(1);
        setMonth(1);
        setYear(1);
        sDate = getString();
    }

    public int getDay() {
        return day;
    }

    public void setDay(int day) {
        this.day = day;
        sDate = getString();
    }

    public int getMonth() {
        return month;
    }

    public void setMonth(int month) {
        this.month = month;

        sDate = getString();
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;

        sDate = getString();
    }

    public void addDay() {
        day++;
        if (day > DAYS_PER_MONTH) {
            day = 1;
            month++;
            if (month > MONTHS_PER_YEAR) {
                month = 1;
                year++;
            }
        }

        sDate = getString();
    }

    public String getString() {
        // Days since the start: drives and pay go by days.  Towns showed
        // day / month / year, which read as "1/1/1"
        return Messages.getString("Date.0") + getDaysSinceStart(); //$NON-NLS-1$
    }

    /**
     * @return the day number the top bar shows, 1 on the first day
     */
    public int getDaysSinceStart() {
        return (year - 1) * MONTHS_PER_YEAR * DAYS_PER_MONTH + (month - 1) * DAYS_PER_MONTH + day;
    }

    public String toString() {
        if (sDate == null) {
            sDate = getString();
        }

        return sDate;
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        day = in.readInt();
        month = in.readInt();
        year = in.readInt();

        sDate = getString();
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(day);
        out.writeInt(month);
        out.writeInt(year);
    }
}
