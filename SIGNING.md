# التوقيع (Signing)

اسم الحزمة: `app.semo.notes`

## الأسرار المطلوبة في GitHub
المستودع ← Settings ← Secrets and variables ← Actions ← New repository secret:

| الاسم | القيمة |
|---|---|
| `KEYSTORE_BASE64` | محتوى الملف `keystore.base64.txt` كاملًا (سطر واحد) |
| `KEYSTORE_PASSWORD` | كلمة مرور الملف |
| `KEY_ALIAS` | `notes` |
| `KEY_PASSWORD` | كلمة مرور المفتاح (نفس كلمة مرور الملف) |

بعدها شغّل Actions ← **Build APK**. يفشل البناء الرسمي عمدًا إن لم تُضبط هذه الأسرار، كي لا يُنتَج APK بمفتاح عشوائي.

## بدائل إنشاء المفتاح
- على جهازك: `./tools/make-keystore.sh`
- من GitHub: Actions ← **Generate signing key** (مستودع Private فقط) ثم نزّل الـ Artifact.

## تحذيرات
- احتفظ بنسخة من `release.jks` وكلمة المرور خارج GitHub. **فقدانه = لا يمكن تحديث التطبيق أبدًا.**
- لا ترفع `release.jks` ولا `signing-info.txt` إلى المستودع (`.gitignore` يمنع `*.jks`).
- بصمة SHA-256 للشهادة تظهر في ملخص تشغيل البناء، وهي التي تُسجَّل في Play Console.

## تسجيل الدخول بـ Google
سجّل بصمة SHA-1 لهذا المفتاح في Firebase (Project settings ← تطبيق Android `app.semo.notes`):
`BD:99:E8:37:77:70:CE:69:A1:C2:CD:C0:3F:B7:79:59:A9:46:AA:9F`
وأضف سرًّا باسم `WEB_CLIENT_ID` (انظر README).
