package com.lostark.invenchecker.service;

import com.lostark.invenchecker.model.BoardPost;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class InvenSearchService {

    private static final Logger log = LoggerFactory.getLogger(InvenSearchService.class);

    private static final String MOBILE_BASE_URL = "https://m.inven.co.kr";
    private static final Pattern STERM_PATTERN = Pattern.compile("[?&]sterm=([^&]+)");
    private static final long STERM_STEP = 10_000L;

    /** sterm은 페이지마다 10000씩 증가하므로, 1번째 페이지로 시작 sterm을 알면 나머지를 계산해 동시 fetch */
    private static final ExecutorService PAGE_EXECUTOR  = Executors.newFixedThreadPool(15);
    /** 게시글 본문 대상자 확인을 동시에 진행 */
    private static final ExecutorService POST_EXECUTOR  = Executors.newFixedThreadPool(10);

    /**
     * "■ 게임 닉네임" 섹션 추출 패턴.
     * "작성 목적" 등 앞 섹션의 텍스트가 오탐을 일으키지 않도록,
     * 대상자 탐색 범위를 게임 닉네임 섹션 이후로 한정합니다.
     * 섹션 마커가 없는 비정형 게시글은 전체 텍스트를 fallback으로 사용합니다.
     */
    private static final Pattern GAME_NICKNAME_SECTION_PATTERN = Pattern.compile(
            "게임\\s*닉네임[^\\n]*\\n(.+?)(?=\\n\\s*■|$)",
            Pattern.DOTALL
    );

    /**
     * "대상자:" 섹션 종료 조건 (우선순위 순):
     *   - \n\n  : 빈 줄 — 가장 일반적인 섹션 구분 (오탐 방지의 핵심)
     *   - \n■   : 다음 ■ 섹션 구분자
     *   - \n사건 / \n증거 : ■ 없이 쓰는 경우 대응
     *   - $     : 위 마커가 없는 경우 문자열 끝
     *
     * ⚠️ \n\n을 종료 조건에 포함하는 이유:
     * "대상자: 카제로스 / 낯선블레이드\n\n오픈 가능" 같이 빈 줄 이후 본문 내용이
     * 대상자 목록으로 오탐되는 것을 막기 위함.
     *
     * 단, "대상자: 닉네임\n\n실제닉네임1 실제닉네임2" 처럼 템플릿 라벨(닉네임) +
     * 빈 줄 + 실제 목록 구조는 matchesTargetSection에서 별도 처리.
     */
    private static final Pattern TARGET_SECTION_PATTERN = Pattern.compile(
            "대상자\\s*:\\s*(.+?)(?=\\n\\s*\\n|\\n\\s*(?:■|사건|증거)|$)",
            Pattern.DOTALL
    );

    /** 빈 줄 이후 다음 단락을 캡처 (템플릿 라벨 패턴 대응용) */
    private static final Pattern NEXT_PARAGRAPH_PATTERN = Pattern.compile(
            "\\n\\s*\\n(.+?)(?=\\n\\s*\\n|\\n\\s*(?:■|사건|증거)|$)",
            Pattern.DOTALL
    );

    @Value("${inven.board.url:https://m.inven.co.kr/board/lostark/5355}")
    private String boardUrl;

    @Value("${inven.search.max-iterations:15}")
    private int maxIterations;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 사사게에서 닉네임이 "대상자:" 섹션에 포함된 게시글을 검색합니다.
     *
     * 성능 최적화 전략:
     *   1. 첫 페이지(sterm 없음)를 fetch해 시작 sterm을 파악
     *   2. 나머지 (maxIterations-1)개 페이지를 병렬 fetch
     *      (sterm은 10000씩 증가하므로 URL을 미리 계산 가능)
     *   3. 모든 페이지의 게시글 본문 확인을 병렬 fetch
     */
    public List<BoardPost> search(String nickname) throws IOException {
        String encodedNickname = URLEncoder.encode(nickname, StandardCharsets.UTF_8);
        log.info("[검색 시작] 닉네임='{}' | maxIterations={}", nickname, maxIterations);

        // 1. 첫 페이지 fetch (sterm 없음) → 시작 sterm 확인
        String firstUrl = boardUrl + "?stype=content&svalue=" + encodedNickname;
        log.debug("[1/{}] GET {}", maxIterations, firstUrl);
        Document firstDoc = fetchDocument(firstUrl);

        // 2. 나머지 페이지를 병렬 fetch
        List<Document> allDocs = new ArrayList<>();
        allDocs.add(firstDoc);

        String firstSterm = extractNextSterm(firstDoc);
        if (firstSterm != null && maxIterations > 1) {
            long stermBase = Long.parseLong(firstSterm);
            List<CompletableFuture<Document>> pageFutures = new ArrayList<>();

            for (int i = 1; i < maxIterations; i++) {
                long sterm = stermBase + (long)(i - 1) * STERM_STEP;
                String pageUrl = boardUrl + "?stype=content&svalue=" + encodedNickname + "&sterm=" + sterm;
                log.debug("[{}/{}] 병렬 요청 sterm={}", i + 1, maxIterations, sterm);
                pageFutures.add(CompletableFuture.supplyAsync(() -> {
                    try { return fetchDocument(pageUrl); }
                    catch (IOException e) {
                        log.warn("[페이지 fetch 실패] {} - {}", pageUrl, e.getMessage());
                        return null;
                    }
                }, PAGE_EXECUTOR));
            }

            pageFutures.stream().map(CompletableFuture::join).filter(Objects::nonNull).forEach(allDocs::add);
        } else if (firstSterm == null) {
            log.info("[검색 종료] 다음 검색 링크 없음 (1회 수행)");
        }

        // 3. 모든 페이지에서 게시글 목록 파싱
        List<BoardPost> allPosts = allDocs.stream()
                .flatMap(doc -> parsePosts(doc).stream())
                .collect(Collectors.toList());
        log.debug("[파싱] {}개 페이지에서 {}건 추출 → 대상자 확인 시작", allDocs.size(), allPosts.size());

        // 4. 게시글 본문 대상자 확인 (병렬)
        List<BoardPost> verified = filterByTargetSection(allPosts, nickname);
        log.info("[검색 완료] 닉네임='{}' | {}건 중 {}건 수집", nickname, allPosts.size(), verified.size());
        return verified;
    }

    // -----------------------------------------------------------------------
    // 대상자 섹션 필터링 (병렬)
    // -----------------------------------------------------------------------

    private List<BoardPost> filterByTargetSection(List<BoardPost> posts, String nickname) {
        if (posts.isEmpty()) return List.of();

        List<CompletableFuture<BoardPost>> futures = posts.stream()
                .map(post -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return isNicknameInTargetSection(post.getLink(), nickname) ? post : null;
                    } catch (IOException e) {
                        log.warn("[대상자 확인 실패] {} - {}", post.getLink(), e.getMessage());
                        return null;
                    }
                }, POST_EXECUTOR))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean isNicknameInTargetSection(String postLink, String nickname) throws IOException {
        String postUrl = resolvePostUrl(postLink);
        log.debug("[대상자 확인] GET {}", postUrl);
        Document doc = fetchDocument(postUrl);
        String text = extractPostText(doc);
        boolean matched = matchesTargetSection(text, nickname);
        log.debug("[대상자 확인] '{}' → {}", nickname, matched ? "일치" : "불일치");
        return matched;
    }

    private String extractPostText(Document doc) {
        // 댓글·스크립트 등 본문과 무관한 영역을 문서에서 먼저 제거
        // #cmtComment, #i-comment-list : 인벤 모바일 댓글 컨테이너
        doc.select("#cmtComment, #i-comment-list, script, style").remove();

        // ⚠️ 인벤 모바일은 id="articleContent"가 아닌 class="articleContent"를 사용하는 경우가 있음
        // → #articleContent(ID) 와 .articleContent(class) 모두 시도
        Element content = doc.selectFirst("#powerbbsContent, #articleContent, .articleContent");
        if (content == null) content = doc.selectFirst(".article-content, .post-content, .view-content");
        if (content == null) content = doc.body();
        if (content == null) return "";

        String html = content.html();
        html = html.replaceAll("(?i)<br[^>]*>", "\n");
        html = html.replaceAll("(?i)</(div|p|li)>", "\n");
        html = html.replace("&nbsp;", " ");
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");
        html = html.replaceAll("<[^>]+>", "");
        return html;
    }

    boolean matchesTargetSection(String text, String nickname) {
        // 1. "게임 닉네임" 섹션이 있으면 그 범위 안에서만 탐색
        //    → "작성 목적" 등 앞 섹션의 오탐 방지
        Matcher sectionMatcher = GAME_NICKNAME_SECTION_PATTERN.matcher(text);
        String searchScope = sectionMatcher.find() ? sectionMatcher.group(1) : text;

        // 2. 앞뒤로 한글·영문·숫자가 붙어있지 않은 독립 단어로만 매칭
        //    예) "오픈" 검색: "오픈가능" → 오탐 없음, "오픈 고수NK" → 매칭
        Pattern nicknamePattern = Pattern.compile(
                "(?<![가-힣a-zA-Z0-9])" + Pattern.quote(nickname) + "(?![가-힣a-zA-Z0-9])"
        );

        Matcher matcher = TARGET_SECTION_PATTERN.matcher(searchScope);
        while (matcher.find()) {
            String section = matcher.group(1).trim();

            if (nicknamePattern.matcher(section).find()) return true;

            // 3. 템플릿 라벨 패턴 처리:
            //    "대상자: 닉네임\n\n실제닉네임1 실제닉네임2" 구조에서
            //    section이 문자 그대로 "닉네임"이면 빈 줄 다음 단락도 확인
            if ("닉네임".equals(section)) {
                Matcher nextPara = NEXT_PARAGRAPH_PATTERN.matcher(searchScope);
                nextPara.region(matcher.end(), searchScope.length());
                if (nextPara.find() && nicknamePattern.matcher(nextPara.group(1)).find()) return true;
            }
        }
        return false;
    }

    private String resolvePostUrl(String postLink) {
        try {
            URI boardUri = new URI(boardUrl);
            URI postUri  = new URI(postLink);
            String base = boardUri.getScheme() + "://" + boardUri.getHost()
                    + (boardUri.getPort() != -1 ? ":" + boardUri.getPort() : "");
            return base + postUri.getPath();
        } catch (Exception e) {
            return postLink.split("\\?")[0];
        }
    }

    // -----------------------------------------------------------------------
    // HTTP / HTML 파싱
    // -----------------------------------------------------------------------

    private Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .timeout(10000)
                .get();
    }

    private List<BoardPost> parsePosts(Document doc) {
        List<BoardPost> posts = new ArrayList<>();
        for (Element row : doc.select("section.mo-board-list li.list")) {
            Element linkEl = row.selectFirst("a.contentLink");
            if (linkEl == null) continue;

            String title = row.select("span.subject").text().trim();
            if (title.isEmpty()) continue;

            String href = linkEl.attr("href");
            String link = href.startsWith("http") ? href : MOBILE_BASE_URL + href;

            Element authorEl = row.selectFirst("span.nick span.layerNickName");
            String author = authorEl != null ? authorEl.ownText().trim() : "";

            posts.add(new BoardPost(
                    title, link, author,
                    row.select("span.time").text(),
                    row.select("span.view").text().replaceFirst("^조회\\s*", "").trim(),
                    row.select("span.reco").text().replaceFirst("^추천\\s*", "").trim()
            ));
        }
        return posts;
    }

    private String extractNextSterm(Document doc) {
        Element nextLink = doc.selectFirst("a.search-total");
        if (nextLink == null) return null;
        Matcher matcher = STERM_PATTERN.matcher(nextLink.attr("href"));
        return matcher.find() ? matcher.group(1) : null;
    }
}
