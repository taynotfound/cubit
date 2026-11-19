# Paid ads for Cubit

## Where ads can work

Cubit isn't on the Play Store or the App Store, which rules out the usual "app install" ads on Google, Meta and TikTok:
those need a store listing. What's left is **link ads to the browser player**
(https://taynotfound.github.io/cubit/play.html). That's a good fit anyway: someone who taps the ad is playing within two
seconds, with nothing to install, and the win sheet points them to the app.

| Where | Fits? | Why |
|---|---|---|
| **Reddit** | **Best** | Can target communities directly (r/puzzles, r/AndroidGaming, r/fossdroid, r/puzzlevideogames, r/WebGames). Link ads to a website are fine. Minimum $5/day per campaign. |
| Mastodon, Lemmy | No ads exist | Post as a person instead (see LAUNCH.md). |
| Google Ads / Meta | Weak | Install campaigns need a store listing; link ads work but need their tracking pixels for anything useful, which goes against Cubit's no-tracking promise. |
| itch.io | Free | Make a free web game page that embeds play.html; itch's browser-game crowd finds it by tag. |
| Newsletters and YouTubers | Good, often free | Small FOSS and puzzle newsletters, and channels that review F-Droid apps. Ask first; some take a small sponsorship. |

## A first Reddit test

Free games don't earn the money back, so treat this as a small experiment, not a growth engine.

- **Budget:** $10/day for 14 days, about $140. Reported cost per click for gaming audiences is usually $0.75–$2, so
  expect very roughly 70–190 people playing a cube. (These are blog estimates, not Reddit's figures.)
- **Objective:** Traffic (clicks), not conversions: conversions need Reddit's pixel.
- **Targeting:** Communities: r/puzzles, r/puzzlevideogames, r/AndroidGaming, r/WebGames, r/fossdroid, r/opensource,
  r/sudoku, r/crossword. Also try the interest "Puzzle games".
- **Link:** `https://taynotfound.github.io/cubit/play.html`, which always opens today's cube.
- **Creatives:** run two or three at once and let Reddit favour the winner.
  - Video: a 4:5 cut of `docs/img/reel-hook.mp4` ("Can you free the pink arrow?"), made with the ffmpeg line under
    "Making more creatives". Video usually beats stills for games.
  - Stills (in `promo/ads/`): `ad-pink-portrait.png`, `ad-daily-portrait.png`, `ad-noads-square.png`.
- **Headline ideas** (Reddit shows a line of text above the creative):
  - "A daily puzzle: free the pink arrow from a cube of 140 arrows. Plays in your browser."
  - "Made a puzzle game with no ads, no accounts, no tracking. Today's cube takes about 3 minutes."
  - "Can you hit par? Same cube for everyone today."
- **Comments:** keep them on and answer them. On Reddit, a dev who replies under their own ad does noticeably better,
  and it's where the honest feedback is.

## Knowing whether it worked

The site has no analytics, by design, so measure it roughly instead:

- **"Players on the board"** on the homepage (from the leaderboard server), before and after.
- **GitHub release downloads:** `https://api.github.com/repos/taynotfound/cubit/releases` lists each APK's
  `download_count`.
- **Reddit's own numbers** for impressions and clicks.

If you want to see which ad brought people in, a privacy-friendly option is a counter on the leaderboard server that
only adds one to a number per `?ref=` tag, with nothing stored about the visitor. That's a change to the server and a
decision about Cubit's privacy promise, so it's left out for now.

## Making more creatives

- Stills: `python3 tools/ads.py <cube.png> promo/ads .` with a transparent render of the cube (a screenshot of
  play.html's canvas with the page background off). Edit `COPY` in the script for new headlines.
- 4:5 video from a 9:16 reel:
  `ffmpeg -i docs/img/reel-hook.mp4 -vf "scale=-2:1350,pad=1080:1350:(ow-iw)/2:0:color=0x0E1B3D" -c:v libx264 -crf 20 -pix_fmt yuv420p -c:a aac -movflags +faststart out.mp4`
- The 9:16 reels in `docs/img/` are ready as they are for TikTok, Instagram Reels and YouTube Shorts (as posts; those
  platforms' ad tools want store listings).
