# ParentOps — Mobile App Architecture

Target: iOS + Android app that reads both kids' Google Classroom accounts, syncs continuously, and drives a morning-checklist / evening-review daily rhythm with push notifications.

## Feasibility summary

| Requirement | Possible? | How |
|---|---|---|
| One app for iOS + Android | ✅ | Flutter (recommended) or React Native — single codebase, both stores |
| Read from kids' Classroom accounts | ✅ | Google OAuth sign-in per child account with read-only Classroom + Drive scopes |
| Continuous sync | ✅ | Pub/Sub push for coursework changes + 15-min polling for announcements/materials |
| Push notifications | ✅ | Firebase Cloud Messaging (FCM) — covers both Android and iOS (via APNs) |
| Morning checklist digest | ✅ | Server-side scheduled job (7:00 AM) composing from action-item state |
| Evening progress review | ✅ | Server-side scheduled job (7:00 PM) over the same state |

## System overview

```
┌─────────────┐     OAuth tokens      ┌──────────────────┐
│  Flutter app │◄────────────────────►│  Backend service  │
│ (iOS+Android)│   REST/WebSocket     │ (Node or Python)  │
└──────┬──────┘                       └───────┬──────────┘
       │ FCM push                             │
       ▼                                      ▼
┌─────────────┐                   ┌───────────────────────┐
│ Firebase FCM │◄──────────────── │  Sync engine           │
└─────────────┘   digests/alerts  │  • Pub/Sub push        │
                                  │    (courseWork changes)│
                                  │  • Poller every 15 min │
                                  │    (announcements,     │
                                  │     materials)         │
                                  │  • Drive fetch for     │
                                  │    attachments         │
                                  └──────────┬────────────┘
                                             ▼
                                  ┌───────────────────────┐
                                  │  Extraction pipeline   │
                                  │  OCR + LLM → action    │
                                  │  items (fixed schema,  │
                                  │  confidence score)     │
                                  └──────────┬────────────┘
                                             ▼
                                  ┌───────────────────────┐
                                  │  Household store       │
                                  │  kids · courses ·      │
                                  │  action items ·        │
                                  │  timetable · documents │
                                  │  · completion state    │
                                  └───────────────────────┘
```

## Classroom access — the honest constraints

**Scopes (all read-only):** `classroom.courses.readonly`, `classroom.announcements.readonly`, `classroom.coursework.me.readonly`, `classroom.courseworkmaterials.readonly`, plus `drive.readonly` for attachments.

**Sync reality:** Classroom's push-notification feed (via Cloud Pub/Sub registrations) only covers *course work changes* and *roster changes*. Announcements and materials — which is where this school posts almost everything — have **no push feed**, so the backend polls them every ~15 minutes. That's fine: circulars don't need sub-minute latency, and 15-min polling for 2 kids × ~10 courses is trivially within API quotas.

**OAuth verification (the key strategic fact):**
- Classroom scopes are *sensitive scopes*. A publicly released app must pass Google's verification (scope justification + demo video). Doable, but a process.
- An app in **Testing** publishing status needs *no verification* and supports up to **100 test users** — far more than one family.
- Therefore: for family/friends use, the app never needs Google verification or even app-store publication (Android: direct APK/internal testing track; iOS: TestFlight). Verification is deferred until this becomes a public product.

**The one unverifiable-from-outside gate:** the school's Workspace admin can block third-party OAuth apps for student accounts. First build step is a 30-minute spike: run the OAuth flow against one kid's account and confirm access. Everything else is de-risked; this is the only true go/no-go.

## The daily rhythm

Completion state (checklist ticks) lives in **our** database, not Classroom — so progress tracking works even though the school posts unstructured announcements.

**7:00 AM — Morning brief (push notification → opens checklist):**
- Anything from yesterday still unticked (carried forward, flagged)
- Today's action items: tests, things to send, fees due today
- Today's timetable (from the one-time-extracted grid) + derived hints ("PT today — sports shoes")

**7:00 PM — Evening review (push):**
- What got ticked today vs. what's still pending
- Tomorrow preview: test tomorrow? item to prepare tonight?
- Anything new that arrived during the day and needs a decision (e.g. a "confirm date" item)

**Immediate pushes (outside the two digests):** only for high-urgency extractions — fee deadline within 48h, "tomorrow" mentions, newly arrived circular with a same-week deadline. Everything else waits for the digests to avoid notification fatigue.

Bonus where the school *does* use real assignments: `studentSubmissions` exposes turned-in state, so homework completion can be auto-detected rather than manually ticked.

## Build phases

1. **Spike (weekend):** OAuth against one kid's account → go/no-go. Then a script that pulls both kids' streams and prints a merged feed.
2. **Backend core (2–3 wks):** token storage, poller, extraction pipeline, action-item store, FCM wiring.
3. **App v1 (3–4 wks):** Flutter — Today screen (per mockup), checklist interactions, confirm-date flow, morning/evening notifications.
4. **Library + search (2 wks):** attachment indexing, subject-wise browse, full-text search.
5. **Polish:** timetable one-time extraction flow, spouse sharing, weekly digest.

Roughly 8–10 weeks part-time for a family-grade v1; the Testing-mode OAuth path means nothing blocks shipping to your own phones.

## Stack recommendation

- **App:** Flutter (single codebase, best-in-class for form-heavy UI, first-class FCM support)
- **Backend:** small Python (FastAPI) or Node service + Postgres; Cloud Run or any cheap VPS
- **Extraction:** Claude API with structured-output schema; OCR only needed for image-only circulars (Drive-native docs export as text)
- **Push:** Firebase Cloud Messaging (handles APNs for iOS)
- **Auth storage:** encrypted refresh tokens, per-child; revocable from the app
