#!/usr/bin/env python3
"""
edgar_check.py — the "is holdings.json still true" check, not a snapshot pass.

holdings.json's own _comment already says the honest thing: share counts drift
with every 10b5-1 sale/grant and SEC amendment, so it needs re-checking against
EDGAR "every so often rather than treating [it] as permanent." This script is
that re-check, automated — run monthly (see ../.github/workflows/edgar-check.yml),
not every snapshot pass, because Form 4s land days apart, not minutes apart, and
SEC asks that you not hit their servers harder than you need to.

What it does, per person in holdings.json who has an "edgarCik":
  1. Pull that CIK's recent filings from SEC's submissions API.
  2. Filter to Form 4 / 4-A filings newer than the last one this script has
     already seen for them (tracked in edgar_state.json).
  3. For each new filing, download and parse the ownership XML and pull every
     nonDerivativeTransaction's issuer ticker, transaction date, and shares
     owned following the transaction.
  4. Write backend/edgar_report.md — a plain diff of "what's in holdings.json
     today" vs. "what the newest Form 4 says" — and update edgar_state.json so
     the same filing isn't re-reported next month.

What it deliberately does NOT do: touch holdings.json itself. Some entries in
that file are a single Form 4's sharesOwnedFollowingTransaction value directly
(e.g. huang/NVDA); others are derived (a reported ownership PERCENTAGE times
shares outstanding, e.g. zuckerberg/page/brin/ballmer) or composite (musk/SPCX
sums Class A + Class B + RSUs/options across multiple filings, which no single
Form 4 line reports). A script can't safely tell those apart or re-derive them
— holdings.json's own _comment is the record of how each figure was actually
built, and that's a judgment call for whoever reviews the report this script
produces. Applying it is deliberately a separate, human, git-diff-reviewed
edit — never something this script commits on its own.

Stdlib only. No API key — the submissions API is public — but SEC does require
an identifying User-Agent (see fair-access guidance at
https://www.sec.gov/os/webmaster-faq#developers) and asks you not to exceed
10 requests/second. Set EDGAR_USER_AGENT in the environment to something with
your own contact info for better compliance than the generic default below.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Any

USER_AGENT = os.environ.get(
    "EDGAR_USER_AGENT",
    "trillionaire-tracker-edgar-check/0.1 (+https://github.com/Hostu404/TrillionaireTracker)",
)
TIMEOUT = 20
REQUEST_GAP_SECONDS = 0.4  # ~2.5 req/s — well under SEC's 10 req/s ceiling

HERE = os.path.dirname(os.path.abspath(__file__))
HOLDINGS_PATH = os.path.join(HERE, "holdings.json")
STATE_PATH = os.path.join(HERE, "edgar_state.json")
REPORT_PATH = os.path.join(HERE, "edgar_report.md")

SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK{cik10}.json"
# The submissions API gives us the accession number + primary document
# filename; the raw ownership XML for a non-derivative filing lives at this
# same path with no xslF345 rendering folder in front of it.
DOCUMENT_URL = "https://www.sec.gov/Archives/edgar/data/{cik}/{accession_nodash}/{document}"


@dataclass
class NewTransaction:
    ticker: str
    transaction_date: str
    shares_transacted: float | None
    shares_owned_after: float | None
    accession: str
    filing_url: str


@dataclass
class PersonResult:
    person_id: str
    name: str
    cik: str
    current_holdings: dict[str, float] = field(default_factory=dict)
    new_transactions: list[NewTransaction] = field(default_factory=list)
    baseline_note: str | None = None
    error: str | None = None


def _get_json(url: str) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "gzip, deflate"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _get_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read().decode("utf-8", errors="replace")


def load_holdings() -> list[dict[str, Any]]:
    with open(HOLDINGS_PATH, encoding="utf-8") as fh:
        return json.load(fh)["people"]


def load_state() -> dict[str, str]:
    """cik -> last accessionNumber this script has already reported on."""
    if not os.path.exists(STATE_PATH):
        return {}
    with open(STATE_PATH, encoding="utf-8") as fh:
        return json.load(fh)


def save_state(state: dict[str, str]) -> None:
    with open(STATE_PATH, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2, sort_keys=True)
        fh.write("\n")


def fetch_new_form4s(cik: str, since_accession: str) -> list[dict[str, Any]]:
    """Form 4 / 4-A filings for this CIK newer than since_accession (required —
    see latest_form4() for the "we've never checked this person before" case,
    which must NOT walk this same path: a prolific filer can have dozens of
    Form 4s a year, and this function has no cap because incremental monthly
    gaps are always small — it relies on the caller never passing None here."""
    cik10 = cik.zfill(10)
    data = _get_json(SUBMISSIONS_URL.format(cik10=cik10))
    recent = data.get("filings", {}).get("recent", {})
    forms = recent.get("form", [])
    accessions = recent.get("accessionNumber", [])
    dates = recent.get("filingDate", [])
    docs = recent.get("primaryDocument", [])

    found = []
    for form, accession, filing_date, primary_doc in zip(forms, accessions, dates, docs):
        if not form.startswith("4"):
            continue
        if accession == since_accession:
            break
        found.append({
            "accession": accession,
            "filingDate": filing_date,
            "primaryDocument": primary_doc,
        })
    # forms/accessions come back newest-first; report them oldest-first
    found.reverse()
    return found


def latest_form4(cik: str) -> dict[str, Any] | None:
    """The single newest Form 4/4-A for this CIK, with no history walk — used
    only to establish a first-time baseline (see check_person). One request,
    no XML download: we just need its accession number to resume from next
    month, not its contents."""
    cik10 = cik.zfill(10)
    data = _get_json(SUBMISSIONS_URL.format(cik10=cik10))
    recent = data.get("filings", {}).get("recent", {})
    forms = recent.get("form", [])
    accessions = recent.get("accessionNumber", [])
    dates = recent.get("filingDate", [])
    for form, accession, filing_date in zip(forms, accessions, dates):
        if form.startswith("4"):
            return {"accession": accession, "filingDate": filing_date}
    return None


def parse_form4_xml(xml_text: str) -> list[dict[str, Any]]:
    """Every non-derivative transaction line: ticker, date, shares moved, and
    shares owned by the reporting person immediately after."""
    root = ET.fromstring(xml_text)

    def text_of(el: ET.Element | None) -> str | None:
        if el is None:
            return None
        value = el.findtext("value")
        return value if value is not None else (el.text or "").strip() or None

    ticker = text_of(root.find("issuer/issuerTradingSymbol")) or "?"

    lines = []
    table = root.find("nonDerivativeTable")
    if table is None:
        return lines
    for txn in table.findall("nonDerivativeTransaction"):
        date = text_of(txn.find("transactionDate"))
        shares_raw = text_of(txn.find("transactionAmounts/transactionShares"))
        after_raw = text_of(txn.find("postTransactionAmounts/sharesOwnedFollowingTransaction"))
        try:
            shares = float(shares_raw) if shares_raw is not None else None
        except ValueError:
            shares = None
        try:
            after = float(after_raw) if after_raw is not None else None
        except ValueError:
            after = None
        lines.append({
            "ticker": ticker,
            "date": date,
            "sharesTransacted": shares,
            "sharesOwnedAfter": after,
        })
    return lines


def check_person(entry: dict[str, Any], state: dict[str, str]) -> tuple[PersonResult, str | None]:
    cik = entry.get("edgarCik")
    result = PersonResult(
        person_id=entry["id"],
        name=entry["name"],
        cik=cik or "",
        current_holdings={h["ticker"]: h["shares"] for h in entry.get("holdings", [])},
    )
    if not cik:
        result.error = "no edgarCik on file — skipped"
        return result, None

    since = state.get(cik)
    if since is None:
        # First time we've ever checked this person: don't walk and download
        # their whole filing history (see fetch_new_form4s' docstring) — just
        # record where "now" is so next month's run has something to diff
        # against.
        try:
            baseline = latest_form4(cik)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
            result.error = f"EDGAR request failed: {exc}"
            return result, None
        if baseline is None:
            result.error = "no Form 4 filings found on EDGAR for this CIK"
            return result, None
        result.baseline_note = (
            f"No prior check on record — baseline set to accession "
            f"{baseline['accession']} ({baseline['filingDate']}). "
            f"Future runs will report activity after this filing."
        )
        return result, baseline["accession"]

    try:
        filings = fetch_new_form4s(cik, since)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        result.error = f"EDGAR request failed: {exc}"
        return result, None

    newest_accession = since
    for filing in filings:
        accession_nodash = filing["accession"].replace("-", "")
        doc_url = DOCUMENT_URL.format(
            cik=cik.lstrip("0") or "0",
            accession_nodash=accession_nodash,
            document=filing["primaryDocument"],
        )
        time.sleep(REQUEST_GAP_SECONDS)
        try:
            xml_text = _get_text(doc_url)
            lines = parse_form4_xml(xml_text)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ET.ParseError) as exc:
            result.new_transactions.append(NewTransaction(
                ticker="?",
                transaction_date=filing["filingDate"],
                shares_transacted=None,
                shares_owned_after=None,
                accession=filing["accession"],
                filing_url=doc_url + f"  (failed to parse: {exc})",
            ))
            newest_accession = filing["accession"]
            continue

        for line in lines:
            result.new_transactions.append(NewTransaction(
                ticker=line["ticker"],
                transaction_date=line["date"] or filing["filingDate"],
                shares_transacted=line["sharesTransacted"],
                shares_owned_after=line["sharesOwnedAfter"],
                accession=filing["accession"],
                filing_url=doc_url,
            ))
        newest_accession = filing["accession"]

    return result, newest_accession


def render_report(results: list[PersonResult]) -> str:
    lines = [
        "# EDGAR check — new Form 4 activity since the last run",
        "",
        "Generated by `backend/edgar_check.py`. This is a report, not an edit —",
        "nothing here has been written back to `holdings.json`. Some entries there",
        "are derived from a reported percentage or summed across multiple filings",
        "(see `holdings.json`'s own `_comment`), so a number below is not always a",
        "safe drop-in replacement — cross-check the sourcing note for that person",
        "before changing anything.",
        "",
    ]
    any_activity = False
    baselines = [r for r in results if r.baseline_note]
    if baselines:
        lines.append("## First run for some people")
        lines.append("")
        for r in baselines:
            lines.append(f"- **{r.name}** ({r.person_id}): {r.baseline_note}")
        lines.append("")

    for r in results:
        if r.error:
            lines.append(f"## {r.name} ({r.person_id}) — {r.error}")
            lines.append("")
            continue
        if r.baseline_note or not r.new_transactions:
            continue
        any_activity = True
        lines.append(f"## {r.name} ({r.person_id})")
        lines.append("")
        lines.append("Currently in `holdings.json`: " + ", ".join(
            f"{ticker} {shares:,.0f} sh" for ticker, shares in r.current_holdings.items()
        ) or "(none)")
        lines.append("")
        for t in r.new_transactions:
            after = f"{t.shares_owned_after:,.0f} sh" if t.shares_owned_after is not None else "?"
            moved = f"{t.shares_transacted:,.0f} sh" if t.shares_transacted is not None else "?"
            lines.append(
                f"- **{t.ticker}**, {t.transaction_date}: transaction moved {moved}, "
                f"**{after} owned after** — [{t.accession}]({t.filing_url})"
            )
        lines.append("")

    if not any_activity and not baselines:
        lines.append("No new Form 4 filings since the last check for anyone tracked.")
        lines.append("")
    elif not any_activity:
        lines.append("No drift to report yet beyond the baselines above.")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    people = load_holdings()
    state = load_state()

    results: list[PersonResult] = []
    for entry in people:
        result, newest_accession = check_person(entry, state)
        results.append(result)
        if result.cik and newest_accession:
            state[result.cik] = newest_accession
        time.sleep(REQUEST_GAP_SECONDS)

    report = render_report(results)
    with open(REPORT_PATH, "w", encoding="utf-8") as fh:
        fh.write(report)
    save_state(state)

    any_activity = any(r.new_transactions for r in results)
    any_errors = any(r.error for r in results)
    print(report)
    if any_errors:
        print("Completed with errors for one or more people — see report above.", file=sys.stderr)
    return 0  # a per-person fetch error shouldn't fail the whole scheduled run


if __name__ == "__main__":
    sys.exit(main())
