package org.fakehalo.dashclock.extensions.dashportfolio;

import android.app.ActionBar;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.view.MenuItem;

public class PortfolioSettingsActivity extends PreferenceActivity {
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar ab = getActionBar();
        if(ab != null) {
            ab.setIcon(R.drawable.ic_launcher);
            ab.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        setupSimplePreferencesScreen();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("deprecation")
    private void setupSimplePreferencesScreen() {

        // Add 'general' preferences.
        addPreferencesFromResource(R.xml.pref_portfolio);

        // Bind the preferences to their values.  When their values change, their summaries are updated to reflect the new value.
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_SYM_TITLE), false);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_SYMS), false);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_SYMS_ORDER), false);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_CLICK), false);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_CLICK_REVERSE), true);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_HIDE_ON_WEEKENDS), true);
        bindPreferenceSummaryToValue(findPreference(PortfolioExtension.PREF_PORTFOLIO_SHOW_PRICE), true);
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return false;
    }

    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener
            = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {

            // Special treatment for certain keys (order symbols on the fly)
            String stringValue = value.toString();

            // Special treatment for this EditTextPreference, automatically order symbols.
            if(preference.getKey().equals(PortfolioExtension.PREF_PORTFOLIO_SYMS))
                preference.setSummary(PortfolioExtension.normalizeSymbols(stringValue, ", ", 0));

            // Misc. ListPreference handling.
            else if (preference instanceof ListPreference) {
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
            }

            // Misc. EditTextPreference OR CheckBoxPreference in this extension's case.
            else
                preference.setSummary(stringValue);

            return true;
        }
    };

    private static void bindPreferenceSummaryToValue(Preference preference, boolean boolMode) {
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                boolMode
                ? PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getBoolean(preference.getKey(), false)
                : PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), "")
        );
    }
}
