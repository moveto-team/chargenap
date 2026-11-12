#!/usr/bin/env python3
"""공모전 공식 접수양식(DOCX)에 docs/proposal.md 내용을 채운다.

- 02 "앱 프로젝트 소개 / 심사 자료"의 1~8번 답변란에 proposal.md의 "## 1." ~ "## 8." 절을 넣는다.
  (마크다운의 문단, **굵게**, 글머리표, 번호 목록, ### 소제목, 표를 Word 문단·표로 바꾼다.)
- 01 참가신청서와 04 서명란에는 팀명·앱명 등 팀이 정한 값만 넣는다.
  성명·연락처·소속·서명·참가 부문처럼 사람이 직접 적어야 하는 칸은 비워 둔다.

사용:
  python3 scripts/fill_contest_form.py <원본 양식.docx> <출력.docx> [--proposal docs/proposal.md] [--team Moveto]
원본 양식: https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx
표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import argparse
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

APP_NAME = "차지냅 (ChargeNap)"

# ---------------------------------------------------------------- 마크다운 → OOXML

BODY_SZ = 20      # 10pt
TABLE_SZ = 18     # 9pt
HEAD_SZ = 22      # 11pt
CONTENT_WIDTH = 9600  # 답변란 셀 너비(9866) − 좌우 여백


def run(text: str, bold: bool = False, size: int = BODY_SZ, color: str | None = None) -> str:
    props = []
    if bold:
        props.append("<w:b/><w:bCs/>")
    if color:
        props.append(f'<w:color w:val="{color}"/>')
    props.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    return f'<w:r><w:rPr>{"".join(props)}</w:rPr><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def inline_runs(text: str, size: int = BODY_SZ, bold_all: bool = False) -> str:
    """**굵게**와 `코드`만 처리한다. 링크·기호는 글자 그대로 둔다."""
    text = text.replace("`", "")
    out = []
    for i, part in enumerate(re.split(r"\*\*(.+?)\*\*", text)):
        if part:
            out.append(run(part, bold=bold_all or i % 2 == 1, size=size))
    return "".join(out) or run("", size=size)


def para(runs: str, *, before: int = 0, after: int = 80, ind_left: int = 0, hanging: int = 0,
         keep_next: bool = False, jc: str | None = None) -> str:
    ppr = [f'<w:spacing w:before="{before}" w:after="{after}" w:line="300" w:lineRule="auto"/>']
    if ind_left or hanging:
        ppr.append(f'<w:ind w:left="{ind_left}" w:hanging="{hanging}"/>')
    if keep_next:
        ppr.insert(0, "<w:keepNext/>")
    if jc:
        ppr.append(f'<w:jc w:val="{jc}"/>')
    return f'<w:p><w:pPr>{"".join(ppr)}</w:pPr>{runs}</w:p>'


def table(rows: list[list[str]]) -> str:
    ncols = len(rows[0])
    # 열 너비: 글자 수에 비례하되 최소 너비를 둔다.
    lengths = [max(len(r[c]) if c < len(r) else 0 for r in rows) for c in range(ncols)]
    # 짧은 열이 한 글자씩 줄바꿈되지 않도록 최소 너비를, 긴 설명 열이 표를 독차지하지 않도록 상한을 둔다.
    weights = [min(max(l, 10), 38) for l in lengths]
    total = sum(weights)
    widths = [int(CONTENT_WIDTH * w / total) for w in weights]
    widths[-1] += CONTENT_WIDTH - sum(widths)
    border = '<w:{0} w:val="single" w:sz="4" w:space="0" w:color="A9B4C2"/>'
    borders = "".join(border.format(b) for b in ("top", "left", "bottom", "right", "insideH", "insideV"))
    grid = "".join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    xml = [
        f'<w:tbl><w:tblPr><w:tblW w:w="{CONTENT_WIDTH}" w:type="dxa"/><w:tblBorders>{borders}</w:tblBorders>'
        f'<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>'
        f'<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>'
        f'<w:tblGrid>{grid}</w:tblGrid>'
    ]
    for r_i, row in enumerate(rows):
        header = r_i == 0
        tr = ['<w:tr><w:trPr><w:cantSplit/>' + ('<w:tblHeader/>' if header else '') + '</w:trPr>']
        for c in range(ncols):
            cell = row[c] if c < len(row) else ""
            shade = '<w:shd w:val="clear" w:color="auto" w:fill="EEF2F7"/>' if header else ""
            tr.append(
                f'<w:tc><w:tcPr><w:tcW w:w="{widths[c]}" w:type="dxa"/>{shade}</w:tcPr>'
                + para(inline_runs(cell, size=TABLE_SZ, bold_all=header), after=0)
                + "</w:tc>"
            )
        tr.append("</w:tr>")
        xml.append("".join(tr))
    xml.append("</w:tbl>")
    return "".join(xml)


def markdown_to_ooxml(md: str) -> str:
    blocks: list[str] = []
    lines = md.strip("\n").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip() or line.strip() == "---":
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            blocks.append(table(rows))
            blocks.append(para("", after=40))
            continue
        if line.startswith("### "):
            blocks.append(para(run(line[4:], bold=True, size=HEAD_SZ, color="1F3A5F"), before=120, after=60, keep_next=True))
        elif re.match(r"^- ", line):
            blocks.append(para(run("•  ") + inline_runs(line[2:]), ind_left=284, hanging=230, after=40))
        elif re.match(r"^\d+\. ", line):
            num, rest = line.split(" ", 1)
            blocks.append(para(run(num + "  ") + inline_runs(rest), ind_left=340, hanging=340, after=40))
        elif line.startswith("> "):
            blocks.append(para(inline_runs(line[2:]), after=60))
        else:
            blocks.append(para(inline_runs(line)))
        i += 1
    # 셀의 마지막 요소는 문단이어야 한다.
    if not blocks or not blocks[-1].startswith("<w:p>"):
        blocks.append(para(""))
    return "".join(blocks)


def proposal_sections(md: str) -> dict[int, str]:
    """'## 1. 제목' ~ 다음 '## ' 직전까지를 번호별로 돌려준다."""
    sections: dict[int, str] = {}
    for m in re.finditer(r"^## (\d)\. [^\n]*\n(.*?)(?=^## |\Z)", md, re.S | re.M):
        sections[int(m.group(1))] = m.group(2)
    return sections


# ---------------------------------------------------------------- 양식 편집

def top_level_elements(body: str) -> list[tuple[int, int, str]]:
    """<w:body> 안의 최상위 <w:p>/<w:tbl> 요소 (시작, 끝, 종류)."""
    pat = re.compile(r"<(/?)w:(p|tbl)\b[^>]*?(/?)>")
    out, depth, start, kind = [], 0, 0, ""
    for m in pat.finditer(body):
        close, tag, selfclose = m.group(1), m.group(2), m.group(3)
        if depth == 0 and not close:
            start, kind = m.start(), tag
        if selfclose:
            if depth == 0:
                out.append((start, m.end(), kind))
            continue
        depth += -1 if close else 1
        if depth == 0 and close:
            out.append((start, m.end(), kind))
    return out


def paragraph_text(xml: str) -> str:
    return "".join(re.findall(r"<w:t(?: [^>]*)?>([^<]*)</w:t>", xml))


def labeled_line(p_xml: str, label: str, value: str) -> str:
    """'라벨: ______' 형태의 문단을 '라벨: 값'으로 바꾼다. 문단 속성은 그대로 둔다."""
    ppr = re.search(r"<w:pPr>.*?</w:pPr>", p_xml, re.S)
    rpr = '<w:rPr><w:lang w:eastAsia="ko-KR"/></w:rPr>'
    head = p_xml[: p_xml.index(">") + 1]
    return (
        head + (ppr.group(0) if ppr else "")
        + f'<w:r>{rpr}<w:t xml:space="preserve">{escape(label)}</w:t></w:r>'
        + f'<w:r><w:rPr><w:b/><w:lang w:eastAsia="ko-KR"/></w:rPr><w:t xml:space="preserve">{escape(value)}</w:t></w:r>'
        + "</w:p>"
    )


def fill(document_xml: str, sections: dict[int, str], team: str) -> str:
    body_start = document_xml.index("<w:body>") + len("<w:body>")
    body = document_xml[body_start:]
    elements = top_level_elements(body)

    replacements: dict[int, str] = {}
    answer_boxes = []  # 02 장의 답변란(한 칸짜리 표) 순서
    in_part2 = False
    for idx, (s, e, kind) in enumerate(elements):
        xml = body[s:e]
        text = paragraph_text(xml)
        if kind == "p":
            if text.startswith("02"):
                in_part2 = True
            elif text.startswith("03"):
                in_part2 = False
            if text.startswith("참가 단위:"):
                replacements[idx] = xml.replace("□ 팀", "■ 팀")
            elif text.startswith("팀명(개인 참가 시 성명):"):
                replacements[idx] = labeled_line(xml, "팀명(개인 참가 시 성명): ", team)
            elif text.startswith("팀명(팀 참가 시):"):
                replacements[idx] = labeled_line(xml, "팀명(팀 참가 시): ", team)
            elif text.startswith("앱명 / 프로젝트명:"):
                replacements[idx] = labeled_line(xml, "앱명 / 프로젝트명: ", APP_NAME)
        elif kind == "tbl" and in_part2 and xml.count("<w:tc>") == 1:
            answer_boxes.append(idx)

    if len(answer_boxes) != 8:
        raise SystemExit(f"답변란을 8개 찾아야 하는데 {len(answer_boxes)}개를 찾았습니다. 양식이 바뀌었는지 확인하세요.")

    for number, idx in enumerate(answer_boxes, start=1):
        s, e, _ = elements[idx]
        tbl = body[s:e]
        content = sections.get(number, "").strip()
        if not content:
            content = "해당 없음"
        new_inner = markdown_to_ooxml(content)
        # 셀 안의 문단(콘텐츠 컨트롤 포함)을 채운 내용으로 바꾼다.
        tc_open = re.search(r"<w:tc>(<w:tcPr>.*?</w:tcPr>)", tbl, re.S)
        tc_close = tbl.rindex("</w:tc>")
        # 원본 답변란 행은 '페이지에서 나누지 않기'라 긴 답변이 통째로 다음 쪽으로 밀린다. 나눌 수 있게 푼다.
        filled = tbl[: tc_open.end()] + new_inner + tbl[tc_close:]
        first_split, first_cell = filled.find("<w:cantSplit/>"), filled.find("<w:tc>")
        replacements[idx] = filled.replace("<w:cantSplit/>", "", 1) if -1 < first_split < first_cell else filled

    out = []
    cursor = 0
    for idx, (s, e, _) in enumerate(elements):
        if idx in replacements:
            out.append(body[cursor:s])
            out.append(replacements[idx])
            cursor = e
    out.append(body[cursor:])
    return document_xml[:body_start] + "".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("template", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--proposal", type=Path, default=Path(__file__).resolve().parent.parent / "docs" / "proposal.md")
    ap.add_argument("--team", default="Moveto")
    args = ap.parse_args()

    md = args.proposal.read_text(encoding="utf-8")
    sections = proposal_sections(md)
    # 출처 목록은 7번(참고자료) 답변란 끝에 붙인다.
    sources = re.search(r"^## 출처\n(.*?)(?=^## |\Z)", md, re.S | re.M)
    if sources and 7 in sections:
        sections[7] = sections[7].rstrip() + "\n\n### 출처\n" + sources.group(1)
    missing = [n for n in range(1, 8) if n not in sections]
    if missing:
        raise SystemExit(f"proposal.md에 '## {missing[0]}.' 절이 없습니다.")

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        with zipfile.ZipFile(args.template) as z:
            for info in z.infolist():
                target = (tmpdir / info.filename).resolve()
                if not str(target).startswith(str(tmpdir.resolve())):
                    raise SystemExit(f"안전하지 않은 경로: {info.filename}")
            z.extractall(tmpdir)
        doc = tmpdir / "word" / "document.xml"
        doc.write_text(fill(doc.read_text(encoding="utf-8"), sections, args.team), encoding="utf-8")

        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.template) as src, zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.is_dir():
                    continue
                data = (tmpdir / info.filename).read_bytes()
                dst.writestr(info, data)
    print(f"작성 완료: {args.output}")


if __name__ == "__main__":
    main()
