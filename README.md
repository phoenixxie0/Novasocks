## Novasocks for Android
=========
A [Novasocks](http://www.novaseed.net) client for Android, powered by amazing languages, fork from shadowsocks and modified by Phoenix.xie

## TRAVIS CI STATUS

[Nightly Builds](http://www.novatko.tk/src)

## PREREQUISITES

JDK 1.6+
Android SDK r21+ or Android Studio 0.2.0+

* Local maven dependencies


## BUILD

* Create your key following the instructions at
http://developer.android.com/guide/publishing/app-signing.html#cert

* Create a profile in your settings.xml file in ~/.m2 like this

```xml
  <settings>
    <profiles>
      <profile>
        <activation>
          <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
          <sign.keystore>/absolute/path/to/your.keystore</sign.keystore>
          <sign.alias>youralias</sign.alias>
          <sign.keypass>keypass</sign.keypass>
          <sign.storepass>storepass</sign.storepass>
        </properties>
      </profile>
    </profiles>
  </settings>
```

* Invoke the building like this