package info.dourok.voicebot.lunar;

/**
 * The Vietnamese lunar calendar, computed on the device: no network, no table that runs out.
 *
 * This is Ho Ngoc Duc's algorithm (https://www.informatik.uni-leipzig.de/~duc/amlich/), the one
 * Vietnamese calendars are printed from: new moons and solar terms from Jean Meeus' "Astronomical
 * Algorithms", taken at UTC+7. The time zone matters -- the Chinese calendar uses UTC+8, and the two
 * disagree on some months (Tet 1985 is a day apart).
 *
 * Plain Java on the JDK, so it is tested off the device.
 */
public final class LunarCalendar {
    public static final double TIME_ZONE = 7.0;

    public static final String[] CAN = {"Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    public static final String[] CHI = {"Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};

    /** A lunar date. {@code leap} marks the repeated (nhuận) month. */
    public static final class LunarDate {
        public final int day;
        public final int month;
        public final int year;
        public final boolean leap;

        LunarDate(int day, int month, int year, boolean leap) {
            this.day = day;
            this.month = month;
            this.year = year;
            this.leap = leap;
        }
    }

    private LunarCalendar() {
    }

    /** Julian day number of a Gregorian date (Julian calendar before 15/10/1582). */
    public static int jdFromDate(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) {
            jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        }
        return jd;
    }

    /** {day, month, year} of a Julian day number. */
    public static int[] jdToDate(int jd) {
        int a;
        int b;
        int c;
        if (jd > 2299160) {
            a = jd + 32044;
            b = (4 * a + 3) / 146097;
            c = a - (b * 146097) / 4;
        } else {
            b = 0;
            c = jd + 32082;
        }
        int d = (4 * c + 3) / 1461;
        int e = c - (1461 * d) / 4;
        int m = (5 * e + 2) / 153;
        int day = e - (153 * m + 2) / 5 + 1;
        int month = m + 3 - 12 * (m / 10);
        int year = b * 100 + d - 4800 + m / 10;
        return new int[]{day, month, year};
    }

    /** Day number of the k-th new moon after 1/1/1900, local to {@code tz}. */
    static int newMoonDay(int k, double tz) {
        double t = k / 1236.85;
        double t2 = t * t;
        double t3 = t2 * t;
        double dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 = jd1 + 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * dr);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * dr) + 0.0021 * Math.sin(2 * dr * m);
        c1 = c1 - 0.4068 * Math.sin(mpr * dr) + 0.0161 * Math.sin(dr * 2 * mpr);
        c1 = c1 - 0.0004 * Math.sin(dr * 3 * mpr);
        c1 = c1 + 0.0104 * Math.sin(dr * 2 * f) - 0.0051 * Math.sin(dr * (m + mpr));
        c1 = c1 - 0.0074 * Math.sin(dr * (m - mpr)) + 0.0004 * Math.sin(dr * (2 * f + m));
        c1 = c1 - 0.0004 * Math.sin(dr * (2 * f - m)) - 0.0006 * Math.sin(dr * (2 * f + mpr));
        c1 = c1 + 0.0010 * Math.sin(dr * (2 * f - mpr)) + 0.0005 * Math.sin(dr * (2 * mpr + m));
        double deltaT;
        if (t < -11) {
            deltaT = 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3;
        } else {
            deltaT = -0.000278 + 0.000265 * t + 0.000262 * t2;
        }
        return (int) Math.floor(jd1 + c1 - deltaT + 0.5 + tz / 24);
    }

    /** Sun longitude at the start of day {@code jdn}, as one of 12 sectors of 30 degrees (0 = 0..30). */
    static int sunLongitude(int jdn, double tz) {
        double t = (jdn - 2451545.5 - tz / 24) / 36525;
        double t2 = t * t;
        double dr = Math.PI / 180;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(dr * m);
        dl = dl + (0.019993 - 0.000101 * t) * Math.sin(dr * 2 * m) + 0.000290 * Math.sin(dr * 3 * m);
        double l = (l0 + dl) * dr;
        l = l - Math.PI * 2 * Math.floor(l / (Math.PI * 2));
        return (int) Math.floor(l / Math.PI * 6);
    }

    /** Day number of the start of the 11th lunar month (the one holding the winter solstice) of {@code yy}. */
    static int lunarMonth11(int yy, double tz) {
        int off = jdFromDate(31, 12, yy) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        int nm = newMoonDay(k, tz);
        if (sunLongitude(nm, tz) >= 9) {
            nm = newMoonDay(k - 1, tz);
        }
        return nm;
    }

    /** Which month after month 11 starting at {@code a11} is the leap month, in a 13-month year. */
    static int leapMonthOffset(int a11, double tz) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last;
        int i = 1;
        int arc = sunLongitude(newMoonDay(k + i, tz), tz);
        do {
            last = arc;
            i++;
            arc = sunLongitude(newMoonDay(k + i, tz), tz);
        } while (arc != last && i < 14);
        return i - 1;
    }

    public static LunarDate solarToLunar(int dd, int mm, int yy) {
        double tz = TIME_ZONE;
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = (int) Math.floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoonDay(k + 1, tz);
        if (monthStart > dayNumber) {
            monthStart = newMoonDay(k, tz);
        }
        int a11 = lunarMonth11(yy, tz);
        int b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = lunarMonth11(yy - 1, tz);
        } else {
            lunarYear = yy + 1;
            b11 = lunarMonth11(yy + 1, tz);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (int) Math.floor((monthStart - a11) / 29.0);
        boolean leap = false;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapDiff = leapMonthOffset(a11, tz);
            if (diff >= leapDiff) {
                lunarMonth = diff + 10;
                if (diff == leapDiff) {
                    leap = true;
                }
            }
        }
        if (lunarMonth > 12) {
            lunarMonth = lunarMonth - 12;
        }
        if (lunarMonth >= 11 && diff < 4) {
            lunarYear -= 1;
        }
        return new LunarDate(lunarDay, lunarMonth, lunarYear, leap);
    }

    /** {day, month, year} of a lunar date, or null when that month is not a leap month that year. */
    public static int[] lunarToSolar(int lunarDay, int lunarMonth, int lunarYear, boolean leap) {
        double tz = TIME_ZONE;
        int a11;
        int b11;
        if (lunarMonth < 11) {
            a11 = lunarMonth11(lunarYear - 1, tz);
            b11 = lunarMonth11(lunarYear, tz);
        } else {
            a11 = lunarMonth11(lunarYear, tz);
            b11 = lunarMonth11(lunarYear + 1, tz);
        }
        int k = (int) Math.floor(0.5 + (a11 - 2415021.076998695) / 29.530588853);
        int off = lunarMonth - 11;
        if (off < 0) {
            off += 12;
        }
        if (b11 - a11 > 365) {
            int leapOff = leapMonthOffset(a11, tz);
            int leapMonth = leapOff - 2;
            if (leapMonth < 0) {
                leapMonth += 12;
            }
            if (leap && lunarMonth != leapMonth) {
                return null;
            } else if (leap || off >= leapOff) {
                off += 1;
            }
        } else if (leap) {
            return null;
        }
        int monthStart = newMoonDay(k + off, tz);
        return jdToDate(monthStart + lunarDay - 1);
    }

    /** The leap month of lunar year {@code lunarYear}, or 0 when it has none. */
    public static int leapMonth(int lunarYear) {
        double tz = TIME_ZONE;
        int a11 = lunarMonth11(lunarYear - 1, tz);
        int b11 = lunarMonth11(lunarYear, tz);
        if (b11 - a11 <= 365) {
            return 0;
        }
        int leap = leapMonthOffset(a11, tz) - 2;
        return leap <= 0 ? leap + 12 : leap;
    }

    public static String yearCanChi(int lunarYear) {
        return CAN[mod(lunarYear + 6, 10)] + " " + CHI[mod(lunarYear + 8, 12)];
    }

    public static String monthCanChi(int lunarMonth, int lunarYear) {
        return CAN[mod(lunarYear * 12 + lunarMonth + 3, 10)] + " " + CHI[mod(lunarMonth + 1, 12)];
    }

    public static String dayCanChi(int jd) {
        return CAN[mod(jd + 9, 10)] + " " + CHI[mod(jd + 1, 12)];
    }

    /** Index into {@link #CHI} of the day's earthly branch. */
    public static int dayChi(int jd) {
        return mod(jd + 1, 12);
    }

    /** Index into {@link #CHI} of the year's earthly branch. */
    public static int yearChi(int lunarYear) {
        return mod(lunarYear + 8, 12);
    }

    // For each day branch (by branch index mod 6), which of the 12 two-hour periods are auspicious.
    private static final String[] GIO_HOANG_DAO = {
        "110100101100", "001101001011", "110011010010", "101100110100", "001011001101", "010010110011",
    };

    /** The auspicious hours of a day, e.g. "Tý (23h-1h), Sửu (1h-3h), …". */
    public static String gioHoangDao(int jd) {
        String bits = GIO_HOANG_DAO[dayChi(jd) % 6];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (bits.charAt(i) == '1') {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(CHI[i]).append(" (").append((i * 2 + 23) % 24).append("h-").append((i * 2 + 1) % 24).append("h)");
            }
        }
        return out.toString();
    }

    private static final String[] NAP_AM = {
        "Hải Trung Kim", "Lư Trung Hỏa", "Đại Lâm Mộc", "Lộ Bàng Thổ", "Kiếm Phong Kim", "Sơn Đầu Hỏa",
        "Giản Hạ Thủy", "Thành Đầu Thổ", "Bạch Lạp Kim", "Dương Liễu Mộc", "Tuyền Trung Thủy", "Ốc Thượng Thổ",
        "Tích Lịch Hỏa", "Tùng Bách Mộc", "Trường Lưu Thủy", "Sa Trung Kim", "Sơn Hạ Hỏa", "Bình Địa Mộc",
        "Bích Thượng Thổ", "Kim Bạch Kim", "Phú Đăng Hỏa", "Thiên Hà Thủy", "Đại Trạch Thổ", "Thoa Xuyến Kim",
        "Tang Đố Mộc", "Đại Khê Thủy", "Sa Trung Thổ", "Thiên Thượng Hỏa", "Thạch Lựu Mộc", "Đại Hải Thủy",
    };

    /** The "mệnh" (nạp âm element) of a lunar birth year, e.g. 1999 -> "Thành Đầu Thổ". */
    public static String napAm(int lunarYear) {
        return NAP_AM[mod(lunarYear - 4, 60) / 2];
    }

    private static int mod(int a, int n) {
        return ((a % n) + n) % n;
    }
}
