# Maira – Android voice assistant (Hindi / Marathi / English)

Bolo ya type karo, Gemini AI command samajh ke action plan banata hai, aur app use phone pe chalaati hai.

## Install
1. Repo ke **Releases → latest** se `Maira.apk` download karo.
2. Install karo ("unknown apps" allow karna padega).
3. App kholte hi ⚙ Settings mein apni Gemini API key daalo (free: https://aistudio.google.com/apikey).
4. Permissions (SMS, Call, Contacts, Notifications) allow karo.

## Kya kar sakta hai
Alarm, reminder (notification), SMS, call, app kholna, link kholna, music, WiFi/Bluetooth panel, volume, battery, calendar event.
SMS/Call se pehle hamesha confirm poochhta hai. Banking/UPI/password apps block hain.

## Limits (v1)
- Android 10+ pe WiFi/Bluetooth seedha on/off nahi hota, settings panel khulta hai.
- screen_action (dusre apps ki screen control) abhi supported nahi.
- Har build naye debug key se sign hota hai; update se pehle purana app uninstall karna pad sakta hai.

## Build
Push to `main` → GitHub Actions APK banata hai aur Releases mein daal deta hai.
System prompt: `app/src/main/assets/system_prompt.txt`
