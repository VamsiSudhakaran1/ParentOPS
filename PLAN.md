# ParentOps — Product Plan

Wedge product for the broader "HomeOps Agent" vision: an AI agent that reads school circulars, bills, WhatsApp screenshots, and PDFs, and turns them into reminders, checklists, calendar events, and spouse-ready summaries for Indian parents.

## 1. Doability — is this actually buildable?

**Yes, with a narrow MVP.** The hard parts are well-covered by existing tooling; the differentiated part is the schema and the "so what" summary layer, not the extraction itself.

| Capability | Solved by | Difficulty |
|---|---|---|
| OCR on screenshots/scanned PDFs | Off-the-shelf OCR (Tesseract, cloud vision APIs) | Low |
| Understanding messy circular text (Hinglish, abbreviations, photos of notices) | LLM (Claude) with a structured-extraction prompt + few-shot examples | Medium |
| Extracting structured fields (date, child, fee amount, action, deadline) | LLM function-calling / JSON-mode extraction against a fixed schema | Medium |
| De-duplication ("this is the same circular forwarded twice") | Embedding similarity + hash of normalized text | Low |
| Calendar/reminder creation | Google Calendar API, device calendar (.ics), WhatsApp/SMS reminder push | Low |
| WhatsApp ingestion | WhatsApp Business API (forward-to-bot) or manual "share" intent on Android | Medium (WA Business API approval takes time) |
| Multi-child, multi-parent household model | Standard relational data model (household → children → documents → action items) | Low |

**Biggest real risks are not technical, they're operational:**
- WhatsApp Business API approval/verification and per-message costs.
- OCR/LLM accuracy on low-quality photos of handwritten notes — needs a "confirm before you commit" UX rather than silent auto-scheduling, or wrong dates become a trust-destroying failure.
- Privacy: this product touches children's names, schools, medical records, payment amounts. Needs real data handling discipline (encryption at rest, no training on user data, clear deletion, India data residency story) from day one — not because of some abstract compliance checkbox, but because a single leaked child's info kills the product's trust and the parent-facing narrative.

**Verdict: doable as a 6–8 week MVP by 1-2 engineers**, using upload/forward + LLM extraction + a simple weekly-digest UI. The full "Family Operating System" vision is a multi-year roadmap, not an MVP concern.

## 2. Scope

### In scope for MVP (ParentOps v0)
- Single input surface: user forwards/uploads a circular (image, PDF, or pasted text) via WhatsApp bot or a simple web/mobile upload.
- Extraction schema: child/class, event/action type, date, deadline, amount (if fee), items to bring, dress code, location, contact.
- Output: a per-child weekly view + calendar event + optional reminder notification.
- One-tap "share to spouse" (WhatsApp share link or SMS).
- Manual correction UI — user can edit any extracted field before it's committed (critical trust mechanism, not a nice-to-have).
- Single school, single family unit to start (no school-side dashboard yet).

### Explicitly out of scope for MVP
- Gmail/SMS auto-ingestion (adds OAuth, security review, inbox-parsing complexity — defer to v1).
- Medical, warranty, bills modules (separate wedge, later).
- School-side B2B dashboard / bulk circular distribution (schools are a slow sales cycle — deliberately deferred, per the original thesis).
- Any "waste detection" or subscription-optimization intelligence (v2+, needs bill data at scale first).
- Diagnosis or medical advice of any kind (liability line the product must never cross).

## 3. Action Plan

**Phase 0 (Week 1): Validate**
- Interview 10-15 parents (school WhatsApp groups, personal network) on current circular-handling pain, willingness to forward messages to a bot, and price sensitivity.
- Ship a "concierge MVP": a human (you) manually processes forwarded circulars into a digest for 5 families for 2 weeks. Confirms demand before writing extraction code.

**Phase 1 (Weeks 2–5): Build core extraction + digest**
- Define the JSON schema for an "action item" (child, type, date, deadline, amount, items, notes, source doc).
- Build ingestion: WhatsApp forward-to-number (using WhatsApp Business API or a lightweight bridge like a dedicated bot number) + web upload fallback.
- Build extraction pipeline: OCR → LLM structured extraction → confidence score → user confirmation step.
- Build weekly digest view (web page or WhatsApp message) grouped by child/day.
- Add calendar export (.ics) and spouse-share link.

**Phase 2 (Weeks 6–8): Close the loop**
- Add reminder notifications (day-before nudges for fees/deadlines/dress code).
- Add correction/edit flow and feedback capture ("was this right?") to improve prompts over time.
- Add basic household model: multiple children, multiple parents on one account.
- Instrument usage: circulars processed, edits needed per circular, digest open rate, reminders acted on.

**Phase 3 (post-MVP, based on validated demand):**
- Gmail connector (OAuth) for circulars that arrive by email.
- Bills & renewals module (second wedge, reusing the same extraction/reminder infra).
- Paid tiers rollout (see monetization below).

## 4. Usability

The product only works if it removes work, not adds it. Concrete usability commitments:

- **Zero new habit required to capture data** — parents already forward/screenshot circulars; the only new action is forwarding to one more contact (the bot), not learning an app.
- **Never auto-commit an uncertain extraction.** Low-confidence fields are flagged and require one tap to confirm — this is what prevents "the agent sent my kid in the wrong uniform" trust failures.
- **Weekly digest is the primary surface**, not a dashboard nobody opens. Push it (WhatsApp message or notification) rather than requiring the user to check an app.
- **Spouse-sharing is one tap**, because household coordination (not personal organization) is the real value prop — most of this pain is currently solved badly via screenshot-forwarding between spouses.
- **Graceful degradation**: if extraction fails or confidence is low, still surface the original circular in the digest ("couldn't parse this one, here's the original") rather than silently dropping it. Dropped items are the failure mode that kills trust fastest.

## 5. Existability — is there a real, ownable product here?

**Why it can exist as a business:**
- The pain is real, frequent (multiple times/week per household with school-age kids), and emotionally charged (missed deadlines/dress codes create guilt), which drives retention better than a generic productivity tool.
- It's genuinely underbuilt — existing players are either generic AI assistants (not household-specific) or school-communication apps (built for the school's convenience, not the parent's, and don't unify WhatsApp/email/SMS/paper).
- The data-mess (screenshots, PDFs, forwards, multiple family members) that makes this hard to build is also the moat — a generic LLM wrapper doesn't have the household data model, the schema, or the trust-calibrated UX this needs.
- B2C wedge avoids the slow-moving school procurement cycle; revenue can start from parents directly.

**Why it might not exist / key risks to watch:**
- **Distribution is the hard part, not the tech.** Parents don't search for "circular management app" — this needs word-of-mouth within school parent WhatsApp groups, or partnership with a few schools to seed initial cohorts. Validate a distribution channel in Phase 0, not just the extraction accuracy.
- **WhatsApp dependency risk**: WhatsApp Business API policy/pricing changes, or Meta restricting bot-forwarding patterns, could disrupt the primary ingestion channel — keep upload-based ingestion as a first-class fallback, not an afterthought.
- **Monetization ceiling**: ₹99–199/month is low ARPU; needs either high volume (many households) or expansion into adjacent modules (bills, warranty, medical) to grow LTV — which the plan already anticipates as Phase 3+.
- **Regulatory/trust surface**: children's data + school data + payment amounts means this must treat privacy as a core feature (clear data retention/deletion, no ad-based monetization, India-hosted storage) — get this wrong once publicly and the category-defining trust is gone.

**Verdict:** ParentOps is existable as a narrow, real business if Phase 0 validation confirms parents will actually forward circulars to a bot (behavior change, however small, is the biggest unproven assumption) and a distribution channel (school parent groups) can be seeded. The technology risk is low; the product-market and distribution risk is the real bet.

## 6. Monetization (unchanged from thesis, sequenced)

- Free: up to 10 circulars/month, 1 child.
- ₹99/month: unlimited circulars, 1 child.
- ₹199/month: family plan, multiple children, spouse sharing.
- ₹499/year: school-year plan (annual discount, aligns with school calendar).
- Defer school-side B2B pricing until parent-side traction proves the model.

## 7. Success Metrics for MVP

- % of forwarded circulars correctly extracted without manual correction (target: >70% by end of Phase 2).
- Weekly digest open rate (target: >60%, since a digest nobody reads means the product isn't delivering the "won't slip through the cracks" promise).
- Reminder action rate (did the parent act on a fee/deadline reminder).
- Household retention at week 4 and week 8 (real test of "did this remove work from your life").
- Net new households acquired via referral/spouse-share (validates the "sticky through household coordination" thesis).
