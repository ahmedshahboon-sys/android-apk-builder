from pathlib import Path

root = Path('/tmp/ShahbounMultiEngine')
res = root / 'app/src/main/res'

replacements = {
    "Black's BlackBox": "Shahboun Multi",
    "Setting": "الإعدادات",
    "Settings": "الإعدادات",
    "Installed App": "التطبيقات المثبتة",
    "Installed Apps": "التطبيقات المثبتة",
    "Choose App": "إضافة تطبيق",
    "CHOOSE": "اختيار",
    "Choose": "اختيار",
    "Fake Location(Preview)": "الموقع الوهمي (تجريبي)",
    "Fake Location": "الموقع الوهمي",
    "Device Spoofing": "تغيير هوية الجهاز",
    "Black's BlackBox Gallery": "معرض Shahboun Multi",
    "GMS Manager": "إدارة خدمات Google",
    "Go to Gms Manager": "فتح إدارة خدمات Google",
    "Hide Root": "إخفاء الروت",
    "Daemon": "خدمة الخلفية",
    "Use VPN Network": "استخدام شبكة VPN",
    "VPN mode is not supported on Android 14+": "وضع VPN غير مدعوم على Android 14 فما فوق",
    "Disable Flag Secure": "السماح بلقطات الشاشة",
    "Allow screenshots and screen recording in apps": "السماح بلقطات الشاشة وتسجيل الشاشة داخل التطبيقات",
    "Dark mode": "الوضع الداكن",
    "Use a dark theme for the host UI": "استخدام المظهر الداكن لواجهة التطبيق",
    "Fallback for Some Apps": "وضع التوافق لبعض التطبيقات",
    "Allows the host to communicate with Black's BlackBox": "يسمح للتطبيق الرئيسي بالتواصل مع بيئة Shahboun Multi",
    "Freeze App instantly": "تجميد التطبيق فورًا",
    "Adds a small freeze button to running apps.": "إضافة زر صغير لتجميد التطبيقات أثناء التشغيل.",
    "Send Logs": "إرسال سجل الأخطاء",
    "Create a debug log and share it with any app": "إنشاء سجل تشخيص ومشاركته مع أي تطبيق",
    "About": "حول التطبيق",
    "Author and maintainer": "المطور والمشرف",
    "Tap to open source": "اضغط لفتح المصدر",
    "Build Note": "ملاحظات البناء",
    "Made with Legacy Code and AI.": "تم تطوير هذه النسخة اعتمادًا على الشفرة الأصلية مع تحسينات برمجية.",
    "Others": "أخرى",
    "Automatic freezing is turned off": "التجميد التلقائي متوقف",
    "Activate auto-freeze?": "تفعيل التجميد التلقائي؟",
    "ENABLE": "تفعيل",
    "CANCEL": "إلغاء",
    "Enable": "تفعيل",
    "Cancel": "إلغاء",
    "User ": "المستخدم ",
}

for p in res.rglob('*.xml'):
    try:
        s = p.read_text(encoding='utf-8')
    except Exception:
        continue
    old = s
    for a, b in replacements.items():
        s = s.replace(a, b)
    if s != old:
        p.write_text(s, encoding='utf-8')

manifest = root / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text(encoding='utf-8')
if 'android:supportsRtl=' not in s:
    s = s.replace('<application', '<application android:supportsRtl="true"', 1)
manifest.write_text(s, encoding='utf-8')

(res / 'layout/view_toolbar.xml').write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.appbar.MaterialToolbar xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="72dp"
    android:background="@color/background"
    android:elevation="0dp"
    android:fontFamily="@font/cairo_regular"
    android:paddingStart="20dp"
    android:paddingEnd="12dp"
    android:layoutDirection="rtl"
    app:title="Shahboun Multi"
    app:titleCentered="false"
    app:titleTextColor="@color/on_background"
    app:titleTextAppearance="@style/TextAppearance.MaterialComponents.Headline6" />
''', encoding='utf-8')

(res / 'layout/activity_main.xml').write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layoutDirection="rtl"
    android:background="@color/background">

    <include
        android:id="@+id/toolbar_layout"
        layout="@layout/view_toolbar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/shahboun_subtitle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="22dp"
        android:layout_marginEnd="22dp"
        android:layout_marginTop="2dp"
        android:fontFamily="@font/cairo_regular"
        android:text="كل تطبيقاتك وحساباتك في مكان واحد"
        android:textSize="13sp"
        android:textColor="@color/on_surface_variant"
        android:gravity="start"
        app:layout_constraintTop_toBottomOf="@id/toolbar_layout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/content_card"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="16dp"
        android:layout_marginTop="14dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp"
        app:cardBackgroundColor="@color/surface"
        app:cardCornerRadius="28dp"
        app:cardElevation="2dp"
        app:strokeWidth="0dp"
        app:layout_constraintTop_toBottomOf="@id/shahboun_subtitle"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <com.github.nukc.stateview.StateView
                android:id="@+id/stateView"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />

            <androidx.viewpager2.widget.ViewPager2
                android:id="@+id/viewPager"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:clipToPadding="false"
                android:paddingTop="12dp"
                android:paddingBottom="72dp" />

            <com.tbuonomo.viewpagerdotsindicator.WormDotsIndicator
                android:id="@+id/dots_indicator"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom|center_horizontal"
                android:layout_marginBottom="24dp"
                app:dotsCornerRadius="8dp"
                app:dotsSize="7dp"
                app:dotsSpacing="6dp"
                app:dotsWidthFactor="2.6"
                app:progressMode="true" />
        </FrameLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/freeze_fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:layout_marginBottom="28dp"
        android:contentDescription="@string/auto_freeze_toggle_button"
        android:src="@drawable/ic_snowflake_off"
        app:backgroundTint="@color/surface_variant"
        app:fabSize="mini"
        app:tint="@color/on_surface"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toEndOf="@id/fab" />

    <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
        android:id="@+id/fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="28dp"
        android:layout_marginBottom="24dp"
        android:fontFamily="@font/cairo_regular"
        android:text="إضافة تطبيق"
        android:textSize="14sp"
        android:contentDescription="@string/choose_app"
        app:icon="@drawable/ic_add"
        app:backgroundTint="@color/primary"
        app:iconTint="@color/white"
        android:textColor="@color/white"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
''', encoding='utf-8')

(res / 'layout/fragment_apps.xml').write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layoutDirection="rtl"
    android:background="@color/surface">
    <com.github.nukc.stateview.StateView
        android:id="@+id/stateView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"/>
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingStart="14dp"
        android:paddingTop="18dp"
        android:paddingEnd="14dp"
        android:paddingBottom="28dp"/>
</androidx.constraintlayout.widget.ConstraintLayout>
''', encoding='utf-8')

strings = res / 'values/strings.xml'
if strings.exists():
    s = strings.read_text(encoding='utf-8')
    s = s.replace("Black\\'s BlackBox", "Shahboun Multi").replace("Black's BlackBox", "Shahboun Multi")
    strings.write_text(s, encoding='utf-8')

(root / 'settings.gradle').write_text(r'''pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url "https://www.jitpack.io" }
        maven { url "https://maven.aliyun.com/repository/releases" }
        maven { url "https://maven.aliyun.com/repository/public" }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://www.jitpack.io" }
        maven { url "https://maven.aliyun.com/repository/releases" }
        maven { url "https://maven.aliyun.com/repository/public" }
    }
}
rootProject.name = "NewBlackbox"
include ':app'
include ':blackbox-gallery-stub'
include ':black-reflection'
include ':compiler'
include ':Bcore'
''', encoding='utf-8')
