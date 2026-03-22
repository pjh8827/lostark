package com.lostark.invenchecker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

public class BoardPost {
    private String title;
    private String link;
    private String author;
    private String date;
    private String views;
    private String recommends;

    /** 강화 검색 모드에서 이 게시글을 발견한 캐릭터 닉네임. 기본 검색 시 null (응답 JSON에서 생략됨). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String matchedNickname;

    public BoardPost() {}

    public BoardPost(String title, String link, String author, String date, String views, String recommends) {
        this.title = title;
        this.link = link;
        this.author = author;
        this.date = date;
        this.views = views;
        this.recommends = recommends;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getViews() { return views; }
    public void setViews(String views) { this.views = views; }

    public String getRecommends() { return recommends; }
    public void setRecommends(String recommends) { this.recommends = recommends; }

    public String getMatchedNickname() { return matchedNickname; }
    public void setMatchedNickname(String matchedNickname) { this.matchedNickname = matchedNickname; }
}
