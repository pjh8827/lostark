package com.lostark.invenchecker.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CharacterInfo {

    @JsonProperty("ServerName")
    private String serverName;

    @JsonProperty("CharacterName")
    private String characterName;

    @JsonProperty("CharacterLevel")
    private int characterLevel;

    @JsonProperty("CharacterClassName")
    private String characterClassName;

    @JsonProperty("ItemAvgLevel")
    private String itemAvgLevel;

    public String getServerName() { return serverName; }
    public String getCharacterName() { return characterName; }
    public int getCharacterLevel() { return characterLevel; }
    public String getCharacterClassName() { return characterClassName; }
    public String getItemAvgLevel() { return itemAvgLevel; }
}
