package org.fakehalo.dashclock.extensions.dashportfolio;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;

import android.util.Log;

import android.content.Intent;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

public class PortfolioExtension extends DashClockExtension {
    private static final String TAG = "PortfolioExtension";
    private static final String FINANCE_URL = "http://download.finance.yahoo.com/d/quotes.csv?f=sc6p2&s=";
    public static final String CUSTOM_INDEX = "^MYINDEX"; // Pseudo-symbol to use for the portfolio average index.
    public static final int DEFAULT_TIMEZONE_OFFSET = -18000; // In seconds. (-18000 = EST)
    public static final int START_POLLING_HOUR = 8; // 8AM EST (24hr fmt, broadly account for EDT/EST difference)
    public static final int STOP_POLLING_HOUR = 17; // 5PM EST (24hr fmt, broadly account for EDT/EST difference)
    public static final String PREF_PORTFOLIO_SYM_TITLE = "pref_sym_title";
    public static final String PREF_PORTFOLIO_SYMS = "pref_syms";
    public static final String PREF_PORTFOLIO_SYMS_ORDER = "pref_sym_order";
    public static final String PREF_PORTFOLIO_CLICK = "pref_click";
    public static final String PREF_PORTFOLIO_CLICK_REVERSE = "pref_click_reverse";
    public static final String PREF_PORTFOLIO_SHOW_PRICE = "pref_show_price";
    public static final String PREF_PORTFOLIO_HIDE_ON_WEEKENDS = "pref_hide_on_weekends";

    private long nextEpoch = 0; // next time to check / update symbols.
    private boolean reverseMode = false; // next time to check / update symbols.

    public static class SymbolInfo {
        public String Symbol;
        public double Change;
        public double Percent;
        public boolean Error;
    }

    // Called by dashclock to update.
    @Override
    protected void onUpdateData(int reason) {

        // Get preference value.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String symTitle = sp.getString(PREF_PORTFOLIO_SYM_TITLE, null);
        String syms = sp.getString(PREF_PORTFOLIO_SYMS, null);
        String symOrder = sp.getString(PREF_PORTFOLIO_SYMS_ORDER, null);
        String clickURL = sp.getString(PREF_PORTFOLIO_CLICK, null);
        boolean clickReverse = sp.getBoolean(PREF_PORTFOLIO_CLICK_REVERSE, false);
        boolean hideOnWeekends = sp.getBoolean(PREF_PORTFOLIO_HIDE_ON_WEEKENDS, false);
        boolean showPrice = sp.getBoolean(PREF_PORTFOLIO_SHOW_PRICE, false);

        // isWeekendDay is used after publishUpdate below as well.
        boolean isWeekendDay = false;
        try {
            String today = DateFormat.format("EEEE", new Date()).toString();
            isWeekendDay = (today.equals("Saturday") || today.equals("Sunday"));
        }
        catch (Exception e) {
            debugPrint(TAG, e.getMessage()); // not worth erroring out if this fails, just a dead option.
        }

        // Hide if we're in the weekend and the option is enabled.
        if (hideOnWeekends && isWeekendDay) {
            publishUpdate(new ExtensionData().visible(false));
            return;
        }

        // Force updates if manually activated or configurations change.
        if(reason == UPDATE_REASON_MANUAL || reason == UPDATE_REASON_SETTINGS_CHANGED) {
            nextEpoch = 0; // Forces the update.

            // Always reset reverse mode on changed settings(reason != UPDATE_REASON_SETTINGS_CHANGED), otherwise inverse previous setting(!reverseMode)
            // Unless not enabled(clickReverse), always force to false.
            reverseMode = clickReverse && reason != UPDATE_REASON_SETTINGS_CHANGED && !reverseMode;
        }

        // Get current time in the desired timezone (also used after publishUpdate below), compare to nextUpdate and stop now if updates aren't needed.
        long currentEpoch = new Date().getTime() / 1000 + DEFAULT_TIMEZONE_OFFSET;
        if(nextEpoch > currentEpoch)
            return;

        // Test user-supplied URL for proper syntax, default if a problem. (clickReverse overrides URLs)
        Uri uri = null;
        if(!clickReverse) {
            try {
                if (clickURL != null && !clickURL.isEmpty())
                    uri = Uri.parse(clickURL);
            } catch (Exception e) {
                debugPrint(TAG, e.getMessage());
            }
        }

        try {
            // Create Extension data and fill in defacto defaults.
            ExtensionData ed = new ExtensionData()
                    .visible(true)
                    .icon(R.drawable.ic_launcher)
                    .status("[No Data]")
                    .expandedTitle("[No data available]")
                    .expandedBody("");

            // Add uri if we have a valid one.
            if(uri != null)
                 ed.clickIntent(new Intent(Intent.ACTION_VIEW, uri));

            // Request both the main index and all symbols in one request, if fetchSymbols fails we don't want to publish anything.
            if(fetchSymbols(ed, normalizeSymbols(symTitle + "," + syms, ",", 0), symOrder, symTitle, reverseMode, showPrice)) {

                // We have updated symbol goodies, publish update.
                publishUpdate(ed);

                // See if we should stop polling.
                int currentDailySeconds = (int)(currentEpoch % 86400);
                int currentDailyHours = currentDailySeconds / 3600; // Implicitly floors the hour.

                // Stop polling? If weekend day OR current hour is before ??:00EST OR current hour is after ??:00EST, then prepare the next time we're allowed to poll.
                if(isWeekendDay || currentDailyHours < START_POLLING_HOUR || STOP_POLLING_HOUR <= currentDailyHours) {

                    // Midnight of the current day + START_POLLING_HOUR
                    nextEpoch = currentEpoch - currentDailySeconds + START_POLLING_HOUR * 3600;

                    // We're still in the current day, add 86400 (a day) to go into tomorrow.
                    if(STOP_POLLING_HOUR <= currentDailyHours)
                        nextEpoch += 86400;
                }
            }
        }
        catch (Exception e) {
            debugPrint(TAG, e.getMessage());
        }
    }

    // Various order by logic for symbols.
    private static class SymbolInfoComparator implements Comparator<SymbolInfo> {
        private String symOrder = null;
        public SymbolInfoComparator(String order) {
            symOrder = order;
        }
        public int compare(SymbolInfo s1, SymbolInfo s2) {
            if(symOrder != null && !symOrder.isEmpty()) {
                if (symOrder.equals("percent"))
                    return (int)((s2.Percent*100) - (s1.Percent*100));
                else if (symOrder.equals("percent_reverse"))
                    return (int)((s1.Percent*100) - (s2.Percent*100));
                else if (symOrder.equals("price"))
                    return (int)((s2.Change*100) - (s1.Change*100));
                else if (symOrder.equals("price_reverse"))
                    return (int)((s1.Change*100) - (s2.Change*100));
            }

            // Default to alphabetical if not matched.
            return s1.Symbol.compareTo(s2.Symbol);
        }
    }

    // Take an arbitrary string of symbols and normalize them to a specified standard string.
    public static String normalizeSymbols(String symsIn, String delimiter, int limit) {
        String symsOut = "";
        List<String> symArray = new ArrayList<String>();
        try {
            Pattern p = Pattern.compile("[A-Z0-9.^-]+");
            Matcher m = p.matcher(symsIn.toUpperCase());
            while (m.find()) {
                String sym = m.group(0);
                if(sym.length() < 1 || symArray.contains(sym) || sym.equals(CUSTOM_INDEX)) // Exempt "^MYINDEX" from all symbol lists, it doesn't exist.
                    continue;
                symArray.add(sym);

                // 0 = Unlimited.
                if(limit > 0 && symArray.size() >= limit)
                    break;
            }
            if(symArray.size() > 0) {
                Collections.sort(symArray);
                symsOut = TextUtils.join(delimiter, symArray.toArray());
            }
        }
        catch (Exception e) {
            debugPrint(TAG, e.getMessage());
        }

        // Could potentially be empty from exceptions.
        return symsOut;
    }

    // Convert symbol (index) name to a short description to be used in the header line. (ie. "^GSPC" = "S&P")
    private String symbolNameToShortName(String title) {
        String ret = title;
        try {
            String[] titleNames = getResources().getStringArray(R.array.portfolio_sym_title_values);
            int titleIndex = Arrays.asList(titleNames).indexOf(title);
            if(titleIndex >= 0)
                ret = getResources().getStringArray(R.array.portfolio_sym_title_short_names)[titleIndex];
        }
        catch (Exception e) {
            debugPrint(TAG, e.getMessage());
        }

        // Can return original source string on exception, this is for display purposes and is okay.
        return ret;
    }

    // Call and parse finance url, update ExtensionData as needed. (returns false on critical errors)
    private boolean fetchSymbols(ExtensionData ed, String syms, String symOrder, String symTitle, boolean reverseMode, boolean showPrice)
    {
        // Some basic sanity, shouldn't happen.
        if(syms == null || syms.isEmpty() || symTitle == null ||  symTitle.isEmpty() || ed == null)
            return false;

        String symQuery;
        try { symQuery = URLEncoder.encode(syms, "utf-8"); }
        catch (Exception e) { return false; }

        boolean ret = true; // No early returns from here on.

        // Asynchronous HTTP request not necessary for this extension, dashclock expected to handle anomalies.
        try {
            HttpClient httpclient = new DefaultHttpClient();
            HttpGet httpget = new HttpGet(FINANCE_URL  + symQuery);
            HttpResponse response = httpclient.execute(httpget);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                InputStream is = null;
                try {
                    is = entity.getContent();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    List<SymbolInfo> symbolInfo = new ArrayList<SymbolInfo>(); // Used for all non-main indexes/symbols.
                    SymbolInfo siMaster = null; // Used for the main index/symbol.
                    double allChange = 0, allPercent = 0; // Used for averaging the portfolio. (^MYINDEX)
                    int allErrors = 0; // Used to mark symbols as errors, to be removed from average totals.
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] lineParts = line.split("[^A-Za-z0-9.^_-]+"); // Hokey logic to parse .csv
                        if(lineParts.length >= 4) {
                            SymbolInfo si = new SymbolInfo();
                            si.Symbol = lineParts[1];
                            si.Error = true;
                            try {
                                si.Change = Double.parseDouble(lineParts[2]);
                                si.Percent = Double.parseDouble(lineParts[3]);
                                si.Error = false;
                            }
                            catch (Exception e) {
                                si.Change = 0;
                                si.Percent = 0;
                            }

                            // This is the master/title symbol/index, record it to the master SymbolInfo.
                            if(si.Symbol.equals(symTitle))
                                siMaster = si;

                            // Normal symbol to add to the list of overall symbols, add to overall change/percentage as well.
                            else {
                                symbolInfo.add(si);

                                // If symbol errored don't pollute the average with 0s.
                                if(!si.Error) {
                                    allChange += si.Change;
                                    allPercent += si.Percent;
                                }
                                else
                                    allErrors++;
                            }
                        }
                    }

                    // Add the body (all symbols) if applicable.
                    int totalValidSymbols = symbolInfo.size() - allErrors;
                    if(totalValidSymbols > 0) {

                        // Record portfolio pseudo-index "^MYINDEX" average now that we have gone through all the symbols and we have at least one valid one.
                        try {
                            if(symTitle.equals(CUSTOM_INDEX)) {
                                siMaster = new SymbolInfo();
                                siMaster.Symbol = CUSTOM_INDEX;
                                siMaster.Change = allChange / totalValidSymbols;
                                siMaster.Percent = allPercent / totalValidSymbols;
                                siMaster.Error = false;
                            }
                        }
                        catch (Exception e) {
                            if(siMaster != null)
                                siMaster.Error = true;
                        }

                        // Alphabetical order base for all. (default)
                        Collections.sort(symbolInfo, new SymbolInfoComparator(null));

                        // Apply secondary ordering, if applicable.
                        if(symOrder != null && !symOrder.isEmpty())
                            Collections.sort(symbolInfo, new SymbolInfoComparator(symOrder));

                        if(reverseMode)
                            Collections.reverse(symbolInfo);

                        String bodyLine = "";
                        for (SymbolInfo si : symbolInfo) {
                            String symbolData;
                            if(si.Error)
                                symbolData = "ERR";
                            else if(showPrice)
                                symbolData = String.format("%s%.2f", (si.Change > 0 ? "+" : ""), si.Change);
                            else // Show percentage.
                                symbolData = String.format("%s%.2f%%", (si.Percent > 0 ? "+" : ""), si.Percent);

                            bodyLine += String.format("%s[%s] ", si.Symbol, symbolData);
                        }

                        // Update extension data body.
                        ed.expandedBody(bodyLine);
                    }

                    // Add the status/main title line if applicable.
                    if(siMaster != null) {
                        try {
                            // Similar logic to the normal symbol display, except price change mode (showPrice) isn't allowed.
                            String masterSymbolData = (siMaster.Error ? "ERR" : String.format("%s%.2f%%", (siMaster.Percent > 0 ? "+" : ""), siMaster.Percent));

                            // Update small/extended extension data.
                            ed.status(masterSymbolData).expandedTitle(String.format("%s [%s]", symbolNameToShortName(siMaster.Symbol), masterSymbolData));
                        }
                        catch (Exception e) {
                            debugPrint(TAG, e.getMessage());
                        }
                    }
                }
                catch (Exception e) {
                    debugPrint(TAG, e.getMessage());
                    ret = false;
                }
                finally {
                    try {
                        if(is != null)
                            is.close();
                    }
                    catch (Exception e) {
                        debugPrint(TAG, e.getMessage());
                        ret = false;
                    }
                }
            }
        }
        catch (Exception e) {
            debugPrint(TAG, e.getMessage());
            ret = false;
        }
        return ret;
    }

    private static void debugPrint(String tag, String line) {
        Log.i(tag, line);
    }
}