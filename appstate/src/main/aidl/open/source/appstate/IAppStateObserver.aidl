// IAppStateObserver.aidl
package open.source.appstate;

// Declare any non-default types here with import statements

interface IAppStateObserver {

    void onChanged(boolean isBackground);
}