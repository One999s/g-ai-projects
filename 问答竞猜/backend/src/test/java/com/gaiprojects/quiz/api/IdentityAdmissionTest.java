package com.gaiprojects.quiz.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityAdmissionTest {
  @Autowired MockMvc http;

  @Test
  void statusIsExplicitlyNotProductionReady() throws Exception {
    http.perform(get("/api/quiz/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productionReady").value(false));
  }

  @Test
  void missingIdentityIsDeniedBeforeMalformedBodyParsing() throws Exception {
    http.perform(post("/api/quiz/sessions").contentType("application/json").content("{bad json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("IDENTITY_ADAPTER_NOT_CONFIGURED"));
  }

  @Test
  void contentPublicationFailsClosedWithoutOriginalIdentity() throws Exception {
    for (String operation : java.util.List.of("preview", "publish"))
      http.perform(
              post("/api/quiz/content/packs/" + operation)
                  .contentType("application/json")
                  .content("{bad"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("IDENTITY_ADAPTER_NOT_CONFIGURED"));
  }

  @Test
  void claimedClientScopeCannotCreateIdentity() throws Exception {
    http.perform(
            get("/api/quiz/sessions/00000000-0000-0000-0000-000000000000/current")
                .header("X-User-Id", "42")
                .header("X-Site-Id", "7"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
