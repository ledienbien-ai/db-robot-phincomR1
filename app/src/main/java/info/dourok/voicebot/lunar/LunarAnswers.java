package info.dourok.voicebot.lunar;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the calendar tools say, in words. "Today" is passed in so that it can be the speaker's own
 * day (its time zone) and so that tests can pin it.
 */
public final class LunarAnswers {
    private LunarAnswers() {
    }

    /** {day, month, year} of today in {@code zone}. */
    public static int[] today(TimeZone zone, long nowMs) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(nowMs);
        return new int[]{c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)};
    }

    private static final Pattern DATE = Pattern.compile("^\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{4})\\s*$");

    /** "dd/mm/yyyy" (also with - or .), or blank for {@code today}. */
    public static int[] parseDate(String text, int[] today) {
        if (text == null || text.trim().isEmpty()) {
            return today;
        }
        Matcher m = DATE.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("Ngày phải có dạng ngày/tháng/năm, ví dụ 31/07/1999.");
        }
        int d = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(2));
        int y = Integer.parseInt(m.group(3));
        int[] back = LunarCalendar.jdToDate(LunarCalendar.jdFromDate(d, mo, y));
        if (mo < 1 || mo > 12 || back[0] != d || back[1] != mo || y < 1900 || y > 2199) {
            throw new IllegalArgumentException("Ngày " + text.trim() + " không hợp lệ (hỗ trợ năm 1900 đến 2199).");
        }
        return new int[]{d, mo, y};
    }

    /** The lunar date of a solar date, with its can chi and auspicious hours. */
    public static String lunarDate(int[] solar, int[] today) {
        int jd = LunarCalendar.jdFromDate(solar[0], solar[1], solar[2]);
        LunarCalendar.LunarDate l = LunarCalendar.solarToLunar(solar[0], solar[1], solar[2]);
        String when = sameDay(solar, today) ? "Hôm nay, " : "";
        return when + weekday(jd) + " ngày " + dmy(solar) + " dương lịch là ngày " + l.day + " tháng " + l.month
            + (l.leap ? " nhuận" : "") + " năm " + LunarCalendar.yearCanChi(l.year) + " (" + l.year + ") âm lịch.\n"
            + "Ngày " + LunarCalendar.dayCanChi(jd) + ", tháng " + LunarCalendar.monthCanChi(l.month, l.year)
            + ", năm " + LunarCalendar.yearCanChi(l.year) + ".\n"
            + "Giờ hoàng đạo: " + LunarCalendar.gioHoangDao(jd) + ".";
    }

    /** The solar date of a lunar date; {@code lunarYear} 0 means this lunar year. */
    public static String solarDate(int lunarDay, int lunarMonth, int lunarYear, boolean leap, int[] today) {
        if (lunarMonth < 1 || lunarMonth > 12 || lunarDay < 1 || lunarDay > 30) {
            throw new IllegalArgumentException("Ngày âm lịch không hợp lệ.");
        }
        int year = lunarYear > 0 ? lunarYear : LunarCalendar.solarToLunar(today[0], today[1], today[2]).year;
        int[] solar = LunarCalendar.lunarToSolar(lunarDay, lunarMonth, year, leap);
        if (solar == null) {
            throw new IllegalArgumentException("Năm " + year + " âm lịch không có tháng " + lunarMonth + " nhuận.");
        }
        LunarCalendar.LunarDate back = LunarCalendar.solarToLunar(solar[0], solar[1], solar[2]);
        if (back.day != lunarDay) {
            throw new IllegalArgumentException("Tháng " + lunarMonth + (leap ? " nhuận" : "") + " năm " + year
                + " âm lịch chỉ có 29 ngày.");
        }
        int jd = LunarCalendar.jdFromDate(solar[0], solar[1], solar[2]);
        int days = jd - LunarCalendar.jdFromDate(today[0], today[1], today[2]);
        String from = days == 0 ? "hôm nay" : days > 0 ? "còn " + days + " ngày nữa" : "đã qua " + (-days) + " ngày";
        return "Ngày " + lunarDay + " tháng " + lunarMonth + (leap ? " nhuận" : "") + " năm "
            + LunarCalendar.yearCanChi(year) + " (" + year + ") âm lịch là " + weekday(jd).toLowerCase()
            + " ngày " + dmy(solar) + " dương lịch (" + from + ").";
    }

    private static final String[] ZODIAC = {
        "Ma Kết", "Bảo Bình", "Song Ngư", "Bạch Dương", "Kim Ngưu", "Song Tử",
        "Cự Giải", "Sư Tử", "Xử Nữ", "Thiên Bình", "Bọ Cạp", "Nhân Mã",
    };
    // First day of each month on which the next sign starts (Ma Kết until 19/1, Bảo Bình from 20/1, …).
    private static final int[] ZODIAC_START = {20, 19, 21, 20, 21, 21, 23, 23, 23, 23, 22, 22};

    static String zodiac(int day, int month) {
        int i = day >= ZODIAC_START[month - 1] ? month : month - 1;
        return ZODIAC[i % 12];
    }

    /**
     * The facts a "tử vi hôm nay" reading is written from: the birth date in both calendars, the
     * person's tuổi and mệnh, their star sign, and how today's day branch stands to their year branch.
     */
    public static String horoscopeFacts(int[] birth, int[] today) {
        LunarCalendar.LunarDate b = LunarCalendar.solarToLunar(birth[0], birth[1], birth[2]);
        int todayJd = LunarCalendar.jdFromDate(today[0], today[1], today[2]);
        LunarCalendar.LunarDate t = LunarCalendar.solarToLunar(today[0], today[1], today[2]);
        int birthChi = LunarCalendar.yearChi(b.year);
        int dayChi = LunarCalendar.dayChi(todayJd);
        int age = t.year - b.year + 1;
        return "Ngày sinh " + dmy(birth) + " dương lịch, tức ngày " + b.day + " tháng " + b.month + (b.leap ? " nhuận" : "")
            + " năm " + b.year + " âm lịch.\n"
            + "Tuổi " + LunarCalendar.yearCanChi(b.year) + " (con " + animal(birthChi) + "), mệnh "
            + LunarCalendar.napAm(b.year) + ", năm nay " + age + " tuổi mụ.\n"
            + "Cung hoàng đạo: " + zodiac(birth[0], birth[1]) + ".\n"
            + "Hôm nay " + dmy(today) + " là ngày " + LunarCalendar.dayCanChi(todayJd) + ", tháng "
            + LunarCalendar.monthCanChi(t.month, t.year) + ", năm " + LunarCalendar.yearCanChi(t.year)
            + " (ngày " + t.day + "/" + t.month + " âm lịch).\n"
            + "Ngày " + LunarCalendar.CHI[dayChi] + " với tuổi " + LunarCalendar.CHI[birthChi] + ": "
            + relation(birthChi, dayChi) + ".\n"
            + "Giờ hoàng đạo hôm nay: " + LunarCalendar.gioHoangDao(todayJd) + ".";
    }

    /** How two earthly branches stand to each other, as folk astrology reads it. */
    static String relation(int a, int b) {
        if (a == b) {
            return "cùng chi (ngày trùng tuổi), bình hòa";
        }
        if ((a + b) % 12 == 1) {
            return "lục hợp, ngày tốt cho tuổi này";
        }
        if (a % 4 == b % 4) {
            return "tam hợp, ngày tốt cho tuổi này";
        }
        if ((a - b + 12) % 12 == 6) {
            return "lục xung, ngày xung với tuổi này, nên thận trọng";
        }
        if ((a + b) % 12 == 7) {
            return "lục hại, ngày không thuận cho tuổi này";
        }
        return "không xung không hợp, ngày bình thường";
    }

    private static final String[] ANIMAL = {
        "chuột", "trâu", "hổ", "mèo", "rồng", "rắn", "ngựa", "dê", "khỉ", "gà", "chó", "lợn",
    };

    private static String animal(int chi) {
        return ANIMAL[chi];
    }

    private static final String[] WEEKDAY = {"Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy"};

    private static String weekday(int jd) {
        return WEEKDAY[(jd + 1) % 7];
    }

    private static String dmy(int[] d) {
        return d[0] + "/" + d[1] + "/" + d[2];
    }

    private static boolean sameDay(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }
}
