package com.lostark.invenchecker.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.lostark.invenchecker.model.BoardPost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * InvenSearchService 테스트 (모바일 m.inven.co.kr 기준)
 *
 * 검색 흐름:
 *   1. 첫 요청: ?stype=content&svalue={nickname}  (sterm 없음)
 *   2. 응답 HTML의 a.search-total href에서 sterm 추출
 *   3. 다음 요청: ?stype=content&svalue={nickname}&sterm={extracted}
 *   4. a.search-total 없으면 종료
 */
@SpringBootTest
class InvenSearchServiceTest {

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("inven.board.url", () -> wireMock.baseUrl() + "/board/lostark/5355");
        registry.add("inven.search.max-iterations", () -> "10");
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Autowired
    InvenSearchService searchService;

    // -----------------------------------------------------------------------
    // 스텁 헬퍼
    // -----------------------------------------------------------------------

    /**
     * 첫 번째 요청 (sterm 없음) 스텁 - 반드시 stubStermRequest보다 먼저 등록해야 합니다.
     * WireMock은 나중에 등록된 스텁이 우선하므로, 이 catch-all 스텁이 먼저 등록되면
     * 이후 등록되는 sterm 특정 스텁이 해당 요청에서 우선 적용됩니다.
     */
    private void stubFirstRequest(String svalue, String bodyFile) {
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo(svalue))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBodyFile(bodyFile)));
    }

    /** sterm 지정 요청 스텁 */
    private void stubStermRequest(String svalue, String sterm, String bodyFile) {
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo(svalue))
                .withQueryParam("sterm", equalTo(sterm))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBodyFile(bodyFile)));
    }

    // -----------------------------------------------------------------------
    // BeeA23 시나리오
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BeeA23 검색 - 첫 번째 결과 페이지에서 게시글 발견 후 다음 검색 이어서 진행")
    void search_BeeA23_findsPostsAcrossPages() throws IOException {
        // 첫 요청 → 결과 2건 + 다음 검색(sterm=9774864)
        stubFirstRequest("BeeA23", "inven-search-result.html");
        // 다음 요청(sterm=9774864) → 결과 1건 + 다음 검색 없음(마지막 페이지)
        stubStermRequest("BeeA23", "9774864", "inven-search-result-last.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).hasSize(3);

        BoardPost first = results.get(0);
        assertThat(first.getTitle()).isEqualTo("자존심이 밥먹여주진 않습니다 아만서버 BeeA23님");
        assertThat(first.getAuthor()).isEqualTo("바닐라밀크티");
        assertThat(first.getLink()).isEqualTo("https://m.inven.co.kr/board/lostark/5355/235181?stype=content&svalue=BeeA23");
        assertThat(first.getDate()).isEqualTo("02-27");
        assertThat(first.getViews()).isEqualTo("125");
        assertThat(first.getRecommends()).isEqualTo("2");
    }

    @Test
    @DisplayName("BeeA23 검색 - 총 2회 요청 (첫 페이지 + 마지막 페이지)")
    void search_BeeA23_requestCount() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-result.html");
        stubStermRequest("BeeA23", "9774864", "inven-search-result-last.html");

        searchService.search("BeeA23");

        // a.search-total이 없는 페이지에서 종료 → 총 2회
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/board/lostark/5355")));
    }

    // -----------------------------------------------------------------------
    // HTML 파싱 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("HTML 파싱 - 모바일 셀렉터 정상 동작 확인")
    void search_parsesPostsCorrectly() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-result.html");
        stubStermRequest("BeeA23", "9774864", "inven-search-empty.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).hasSize(2);

        BoardPost first = results.get(0);
        assertThat(first.getTitle()).isEqualTo("자존심이 밥먹여주진 않습니다 아만서버 BeeA23님");
        assertThat(first.getAuthor()).isEqualTo("바닐라밀크티");
        assertThat(first.getDate()).isEqualTo("02-27");
        assertThat(first.getViews()).isEqualTo("125");       // "조회 125" → "125"
        assertThat(first.getRecommends()).isEqualTo("2");    // "추천 2" → "2"
        assertThat(first.getLink()).contains("/board/lostark/5355/235181");
    }

    @Test
    @DisplayName("인증 아이콘 img alt 텍스트가 작성자에 포함되지 않아야 함")
    void search_author_excludesMapleIconAltText() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-result.html");
        stubStermRequest("BeeA23", "9774864", "inven-search-empty.html");

        List<BoardPost> results = searchService.search("BeeA23");

        // 두 번째 게시글 작성자: "다른작성자" (maple img alt "인증 아이콘" 제외)
        assertThat(results.get(1).getAuthor()).isEqualTo("다른작성자");
        assertThat(results.get(1).getAuthor()).doesNotContain("인증 아이콘");
    }

    @Test
    @DisplayName("상대경로 링크에 BASE_URL 자동 접두")
    void search_relativeLink_prefixedWithBaseUrl() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-result.html");
        stubStermRequest("BeeA23", "9774864", "inven-search-empty.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results.get(0).getLink()).startsWith("https://m.inven.co.kr");
    }

    // -----------------------------------------------------------------------
    // sterm 동적 추출 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a.search-total href에서 sterm 추출 후 다음 요청에 사용")
    void search_extractsStermFromNextLink() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-result.html");   // sterm=9774864 포함
        stubStermRequest("BeeA23", "9774864", "inven-search-empty.html");

        searchService.search("BeeA23");

        // 첫 페이지의 a.search-total에서 sterm=9774864를 추출해 두 번째 요청에 사용했는지 확인
        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("sterm", equalTo("9774864")));
    }

    @Test
    @DisplayName("a.search-total 없으면 첫 요청 후 종료 (총 1회)")
    void search_noNextLink_stopsAfterFirstRequest() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-empty.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).isEmpty();
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/board/lostark/5355")));
    }

    // -----------------------------------------------------------------------
    // 검색 파라미터 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("검색 URL에 stype=content, svalue={nickname} 파라미터 포함 확인")
    void search_requestUrl_containsCorrectParams() throws IOException {
        stubFirstRequest("BeeA23", "inven-search-empty.html");

        searchService.search("BeeA23");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo("BeeA23")));
    }

    @Test
    @DisplayName("한글 닉네임(교쩌) 검색 - URL 인코딩 정상 처리")
    void search_koreanNickname_encodedCorrectly() throws IOException {
        stubFirstRequest("교쩌", "inven-search-empty.html");

        searchService.search("교쩌");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("svalue", equalTo("교쩌")));
    }
}
