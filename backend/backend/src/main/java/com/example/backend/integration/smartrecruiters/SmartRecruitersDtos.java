package com.example.backend.integration.smartrecruiters;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersPostingPage(Integer offset, Integer limit, Integer totalFound,
                                  List<SmartRecruitersPostingDto> content) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersPostingDto(String id, String name, String releasedDate, SmartRecruitersLocationDto location,
                                 SmartRecruitersLabelDto typeOfEmployment) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersLocationDto(String city, String region, String country, Boolean remote) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersLabelDto(String id, String label) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersPostingDetailDto(String id, String postingUrl, String applyUrl, SmartRecruitersJobAdDto jobAd) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersJobAdDto(SmartRecruitersSectionsDto sections) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersSectionsDto(SmartRecruitersSectionDto jobDescription, SmartRecruitersSectionDto qualifications,
                                  SmartRecruitersSectionDto additionalInformation) { }

@JsonIgnoreProperties(ignoreUnknown = true)
record SmartRecruitersSectionDto(String title, String text) { }
