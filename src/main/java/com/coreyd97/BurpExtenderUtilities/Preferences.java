package com.coreyd97.BurpExtenderUtilities;

import burp.IBurpExtenderCallbacks;
import burp.IHttpRequestResponse;
import burp.IHttpService;
import burp.IRequestInfo;
import com.coreyd97.BurpExtenderUtilities.TypeAdapter.AtomicIntegerTypeAdapter;
import com.coreyd97.BurpExtenderUtilities.TypeAdapter.ByteArrayToBase64TypeAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class Preferences {

    public enum Visibility {GLOBAL, PROJECT, VOLATILE}

    private ILogProvider logProvider;
    private final String extensionIdentifier;
    private final IGsonProvider gsonProvider;
    private final IBurpExtenderCallbacks callbacks;
    private final HashMap<String, Object> preferences;
    private final HashMap<String, Object> preferenceDefaults;
    private final HashMap<String, Type> preferenceTypes;
    private final HashMap<String, Visibility> preferenceVisibilities;
    private final ArrayList<PreferenceListener> preferenceListeners;
    private ProjectSettingStore projectSettingsStore;

    public Preferences(final String extensionIdentifier, final IGsonProvider gsonProvider,
                       final ILogProvider logProvider, final IBurpExtenderCallbacks callbacks){
        this(extensionIdentifier, gsonProvider, callbacks);
        this.logProvider = logProvider;
    }

    public Preferences(final String extensionIdentifier, final IGsonProvider gsonProvider,
                       final IBurpExtenderCallbacks callbacks){
        this.extensionIdentifier = extensionIdentifier;
        this.gsonProvider = gsonProvider;
        this.callbacks = callbacks;
        this.preferenceDefaults = new HashMap<>();
        this.preferences = new HashMap<>();
        this.preferenceTypes = new HashMap<>();
        this.preferenceVisibilities = new HashMap<>();
        this.preferenceListeners = new ArrayList<>();
        registerRequiredTypeAdapters();
        setupProjectSettingsStore();
    }

    private void registerRequiredTypeAdapters(){
        this.gsonProvider.registerTypeAdapter(AtomicInteger.class, new AtomicIntegerTypeAdapter());
        this.gsonProvider.registerTypeAdapter(byte[].class, new ByteArrayToBase64TypeAdapter());
    }

    private void setupProjectSettingsStore(){
        try{
            //Create store object.
            this.projectSettingsStore = new ProjectSettingStore(this, callbacks, extensionIdentifier);
            String extensionIdentifierEncoded = URLEncoder.encode(extensionIdentifier, "UTF-8");

            ProjectSettingStore backwardCompatStore = new ProjectSettingStore(this, callbacks, "com.coreyd97.burpextenderutilities", extensionIdentifier);
            backwardCompatStore.loadFromSiteMap();
            if(backwardCompatStore.getResponse().length > 0){
                this.projectSettingsStore.loadSettingsFromJson(new String(backwardCompatStore.getResponse()));
                backwardCompatStore.setResponse(null);
                callbacks.addToSiteMap(backwardCompatStore);
                callbacks.excludeFromScope(new URL(backwardCompatStore.getHttpService().toString() + "/" + extensionIdentifierEncoded));
            }

            projectSettingsStore.loadFromSiteMap();

            //Add it to the sitemap
            callbacks.addToSiteMap(this.projectSettingsStore);

            URL scopeURL = new URL(projectSettingsStore.getHttpService().getProtocol(),
                    projectSettingsStore.getHttpService().getHost(),
                    projectSettingsStore.getHttpService().getPort(), "/");
            if(!callbacks.isInScope(scopeURL)){
                callbacks.includeInScope(scopeURL);
            }
        } catch (UnsupportedEncodingException | MalformedURLException e) {
            this.projectSettingsStore = null;
            logError("Could not initiate the project setting store. See the below stack trace for more info.");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logError(sw.toString());
        }
    }

    public void registerSetting(String settingName, Type type){
        registerSetting(settingName, type, null, Visibility.GLOBAL);
    }

    public void registerSetting(String settingName, Type type, Object defaultValue){
        registerSetting(settingName, type, defaultValue, Visibility.GLOBAL);
    }

    public void registerSetting(String settingName, Type type, Visibility visibility){
        registerSetting(settingName, type, null, visibility);
    }

    public void registerSetting(String settingName, Type type, Object defaultValue, Visibility visibility){
        throwExceptionIfAlreadyRegistered(settingName);
        this.preferenceVisibilities.put(settingName, visibility);

        Object value = null;
        switch (visibility){
            case PROJECT: {
                if(projectSettingsStore == null)
                    throw new RuntimeException("The project settings store was not initialised. Project settings cannot be setup.");

                this.projectSettingsStore.registerSetting(settingName, type, defaultValue);
                value = this.projectSettingsStore.getSetting(settingName);
                break;
            }
            case GLOBAL: {
                Object storedValue = getGlobalSettingFromBurp(settingName, type);
                this.preferenceTypes.put(settingName, type);

                if(storedValue != null){
                    this.preferences.put(settingName, storedValue);
                }else{
                    if(defaultValue != null){
                        setGlobalSetting(settingName, defaultValue, this);
                    }else{
                        this.preferences.put(settingName, null);
                    }
                }

                this.preferenceDefaults.put(settingName, defaultValue);
                value = this.preferences.get(settingName);
//                logOutput("Global setting \"" + settingName + "\" registered with type " + type.getTypeName()
//                        + " and default value: " + (defaultValue != null ? defaultValue : "null"));
                break;
            }
            case VOLATILE: {
                this.preferenceTypes.put(settingName, type);
                this.preferences.put(settingName, defaultValue);
                this.preferenceDefaults.put(settingName, defaultValue);
                value = this.preferences.get(settingName);
//                logOutput("Volatile setting \"" + settingName + "\" registered with type " + type.getTypeName()
//                        + " and default value: " + (defaultValue != null ? defaultValue : "null"));
                break;
            }
        }

        logOutput(String.format("Registered setting: [Key=%s, Scope=%s, Type=%s, Default=%s, Value=%s]",
                settingName, visibility, type, defaultValue, value));

    }

    @Deprecated
    public void registerGlobalSetting(String settingName, Type type){
        registerSetting(settingName, type, Visibility.GLOBAL);
    }

    @Deprecated
    public void registerGlobalSetting(String settingName, Type type, Object defaultValue){
        registerSetting(settingName, type, defaultValue, Visibility.GLOBAL);
    }

    @Deprecated
    public void registerProjectSetting(String settingName, Type type) {
        registerSetting(settingName, type, Visibility.PROJECT);
    }

    @Deprecated
    public void registerProjectSetting(String settingName, Type type, Object defaultValue) {
        registerSetting(settingName, type, defaultValue, Visibility.PROJECT);
    }


    @Deprecated
    public void registerVolatileSetting(String settingName, Type type){
        registerSetting(settingName, type, Visibility.VOLATILE);
    }

    @Deprecated
    public void registerVolatileSetting(String settingName, Type type, Object defaultValue){
        registerSetting(settingName, type, defaultValue, Visibility.VOLATILE);
    }

    private void setGlobalSetting(String settingName, Object value, Object eventSource) {
        Type type = this.preferenceTypes.get(settingName);
        Object currentValue = this.preferences.get(settingName);
        String currentValueJson = gsonProvider.getGson().toJson(currentValue, type);
        String newValueJson = gsonProvider.getGson().toJson(value, type);
        //Temporarily removed. Not saving preferences for instance variables.
//        if(newValueJson != null && newValueJson.equals(currentValueJson)) return;

        storeGlobalSetting(settingName, newValueJson);
        this.preferences.put(settingName, value);

        for (PreferenceListener preferenceListener : this.preferenceListeners) {
            preferenceListener.onPreferenceSet(eventSource, settingName, value);
        }
    }

    private void storeGlobalSetting(String settingName, String jsonValue){
        this.callbacks.saveExtensionSetting(settingName, jsonValue);
    }

    private Object getGlobalSettingFromBurp(String settingName, Type settingType) {
        String storedValue = getGlobalSettingJson(settingName);
        if(storedValue == null) return null;

//        logOutput(String.format("Value %s loaded for global setting \"%s\". Trying to deserialize.", storedValue, settingName));
        try {
            return gsonProvider.getGson().fromJson(storedValue, settingType);
        }catch (Exception e){
            logError("Could not load stored setting \"" + settingName
                    + "\". This may be due to a change in stored types. Falling back to default.");
            return null;
        }
    }

    public HashMap<String, Visibility> getRegisteredSettings(){
        return this.preferenceVisibilities;
    }

    public <T> T getSetting(String settingName){
        Visibility visibility = this.preferenceVisibilities.get(settingName);
        if(visibility == null) throw new RuntimeException("Setting " + settingName + " has not been registered!");

        Object value = null;
        switch (visibility){
            case VOLATILE:
            case GLOBAL: {
                value = this.preferences.get(settingName);
                break;
            }
            case PROJECT: {
                value = this.projectSettingsStore.getSetting(settingName);
                break;
            }
        }

        return (T) value;
    }

    public void setSetting(String settingName, Object value){
        setSetting(settingName, value, this);
    }

    public void setSetting(String settingName, Object value, Object eventSource){
        Visibility visibility = this.preferenceVisibilities.get(settingName);
        if(visibility == null) throw new RuntimeException("Setting " + settingName + " has not been registered!");
        switch (visibility) {
            case VOLATILE: {
                this.preferences.put(settingName, value);
                break;
            }
            case PROJECT: {
                this.projectSettingsStore.setSetting(settingName, value);
                break;
            }
            case GLOBAL: {
                this.setGlobalSetting(settingName, value, eventSource);
                return;
            }
        }

        for (PreferenceListener preferenceListener : this.preferenceListeners) {
            preferenceListener.onPreferenceSet(this, settingName, value);
        }
    }

    public Type getSettingType(String settingName) {
        Visibility visibility = this.preferenceVisibilities.get(settingName);
        if(visibility == null) throw new RuntimeException("Setting " + settingName + " has not been registered!");
        switch (visibility){
            case PROJECT: {
                return this.projectSettingsStore.getSettingType(settingName);
            }

            case VOLATILE:
            case GLOBAL: {
                return preferenceTypes.get(settingName);
            }
        }

        return null;
    }

    private String getGlobalSettingJson(String settingName) {
        return this.callbacks.loadExtensionSetting(settingName);
    }

    public void addSettingListener(PreferenceListener preferenceListener){
        this.preferenceListeners.add(preferenceListener);
    }

    public void removeSettingListener(PreferenceListener preferenceListener){
        this.preferenceListeners.remove(preferenceListener);
    }

    public void resetSetting(String settingName){
        Visibility visibility = this.preferenceVisibilities.get(settingName);
        if(visibility == null) throw new RuntimeException("Setting " + settingName + " has not been registered!");
        switch (visibility){
            case PROJECT: {
                this.projectSettingsStore.resetSetting(settingName);
                for (PreferenceListener preferenceListener : this.preferenceListeners) {
                    preferenceListener.onPreferenceSet(null, settingName, projectSettingsStore.getSetting(settingName));
                }
                return;
            }

            case VOLATILE:
            case GLOBAL: {
                Object defaultValue = this.preferenceDefaults.getOrDefault(settingName, null);
                String jsonDefaultValue = gsonProvider.getGson().toJson(defaultValue);
                Object newInstance = gsonProvider.getGson().fromJson(jsonDefaultValue, this.preferenceTypes.get(settingName));
                setGlobalSetting(settingName, newInstance, this);
                return;
            }
        }
    }

    public void resetSettings(Set<String> keys){
        for (String key : keys) {
            resetSetting(key);
        }
    }
    
    public void resetAllSettings(){
        HashMap<String, Visibility> registeredSettings = getRegisteredSettings();
        resetSettings(registeredSettings.keySet());
    }

    IGsonProvider getGsonProvider() {
        return gsonProvider;
    }

    void logOutput(String message){
        if(this.logProvider != null)
            logProvider.logOutput(message);
    }

    void logError(String errorMessage){
        if(this.logProvider != null)
            logProvider.logError(errorMessage);
    }

    private void throwExceptionIfAlreadyRegistered(String settingName){
        if(this.preferenceVisibilities.get(settingName) != null)
            throw new RuntimeException("Setting " + settingName + " has already been registered with " +
                    this.preferenceVisibilities.get(settingName) + " visibility.");
    }
}
