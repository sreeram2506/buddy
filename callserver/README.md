# Buddy call server

Answers calls you miss. Your carrier forwards unanswered calls to a Twilio number; this server connects that call to Gemini Live,
talks to the caller as Buddy, and texts you the message.

## Run the checks
    npm install && npm test

## Deploy (Fly.io example)
1. Twilio: buy a voice+SMS number. Set its *Voice webhook* (HTTP POST) to `https://<your-app>.fly.dev/voice`.
2. `fly launch --no-deploy` in this folder, then set secrets:
       fly secrets set GEMINI_API_KEY=... TWILIO_ACCOUNT_SID=... TWILIO_AUTH_TOKEN=... \
         TWILIO_NUMBER=+1... OWNER_NUMBER=+91... OWNER_NAME=Sreeram PUBLIC_HOST=<your-app>.fly.dev
3. `fly deploy`. Check `https://<your-app>.fly.dev/health` says `ok`.
4. In Buddy: "Set my call number to +1..." then "Turn on Auto mode". Buddy sets "forward when unanswered" with the carrier code
   `**61*<number>**20#` (rings 20 s, then forwards) and cancels it with `##61#` when Auto mode is off.

## Safety
Every webhook's Twilio signature is checked; calls are capped at 4 minutes and `MAX_CONCURRENT_CALLS` (default 5);
Buddy always says it is an assistant, shares nothing about you and never accepts OTPs, payments or commitments.
