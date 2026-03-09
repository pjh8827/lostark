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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InvenSearchService {

    private static final Logger log = LoggerFactory.getLogger(InvenSearchService.class);

    private static final String MOBILE_BASE_URL = "https://m.inven.co.kr";
    private static final Pattern STERM_PATTERN = Pattern.compile("[?&]sterm=([^&]+)");

    @Value("${inven.board.url:https://m.inven.co.kr/board/lostark/5355}")
    private String boardUrl;

    @Value("${inven.search.max-iterations:100}")
    private int maxIterations;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 사사게(서버 사건/사고 게시판)에서 닉네임이 언급된 게시글을 검색합니다.
     *
     * 1. 첫 요청: ?stype=content&svalue={nickname} (sterm 없음)
     * 2. 응답 HTML의 a.search-total 링크에서 sterm 추출
     * 3. 다음 요청: ?stype=content&svalue={nickname}&sterm={extracted}
     * 4. a.search-total 링크가 없으면 종료 (또는 maxIterations 도달 시 종료)
     */
    public List<BoardPost> search(String nickname) throws IOException {
        List<BoardPost> results = new ArrayList<>();
        String encodedNickname = URLEncoder.encode(nickname, StandardCharsets.UTF_8);

        log.info("[검색 시작] 닉네임='{}' | maxIterations={}", nickname, maxIterations);

        String url = boardUrl + "?stype=content&svalue=" + encodedNickname;

        for (int i = 1; i <= maxIterations; i++) {
            log.debug("[{}/{}] GET {}", i, maxIterations, url);

            Document doc = fetchDocument(url);
            List<BoardPost> pagePosts = parsePosts(doc);

            if (!pagePosts.isEmpty()) {
                log.info("[{}/{}] {}건 발견", i, maxIterations, pagePosts.size());
            }
            results.addAll(pagePosts);

            // a.search-total 링크에서 다음 sterm 추출
            String nextSterm = extractNextSterm(doc);
            if (nextSterm == null) {
                log.info("[검색 종료] 다음 검색 링크 없음 ({}회 수행)", i);
                break;
            }

            url = boardUrl + "?stype=content&svalue=" + encodedNickname + "&sterm=" + nextSterm;
        }

        log.info("[검색 완료] 닉네임='{}' | 총 {}건 수집", nickname, results.size());
        return results;
    }

    private Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .timeout(10000)
                .get();
    }

    private List<BoardPost> parsePosts(Document doc) {
        List<BoardPost> posts = new ArrayList<>();

        // 모바일 인벤 구조: section.mo-board-list > ul > li.list
        Elements rows = doc.select("section.mo-board-list li.list");

        for (Element row : rows) {
            Element linkEl = row.selectFirst("a.contentLink");
            if (linkEl == null) continue;

            String title = row.select("span.subject").text().trim();
            if (title.isEmpty()) continue;

            String href = linkEl.attr("href");
            String link = href.startsWith("http") ? href : MOBILE_BASE_URL + href;

            // span.layerNickName 의 직접 텍스트만 (인증 아이콘 img alt 제외)
            Element authorEl = row.selectFirst("span.nick span.layerNickName");
            String author = authorEl != null ? authorEl.ownText().trim() : "";

            String date = row.select("span.time").text();
            // "조회 518" → "518", "추천 6" → "6"
            String views = row.select("span.view").text().replaceFirst("^조회\\s*", "").trim();
            String recommends = row.select("span.reco").text().replaceFirst("^추천\\s*", "").trim();

            posts.add(new BoardPost(title, link, author, date, views, recommends));
        }

        return posts;
    }

    /**
     * HTML에서 a.search-total 링크를 찾아 sterm 파라미터 값을 반환합니다.
     * 링크가 없거나 sterm이 없으면 null 반환 → 검색 종료.
     */
    private String extractNextSterm(Document doc) {
        Element nextLink = doc.selectFirst("a.search-total");
        if (nextLink == null) return null;

        String href = nextLink.attr("href");
        Matcher matcher = STERM_PATTERN.matcher(href);
        return matcher.find() ? matcher.group(1) : null;
    }
}
