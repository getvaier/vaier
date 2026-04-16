package net.vaier.integration.controller;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.GetDnsInfoUseCase.DnsRecordUco;
import net.vaier.application.GetDnsInfoUseCase.DnsZoneUco;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DnsControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getDnsZones_returnsZoneNames() throws Exception {
        when(getDnsInfoUseCase.getDnsZones()).thenReturn(List.of(
                new DnsZoneUco("vaier.net"),
                new DnsZoneUco("example.com")
        ));

        mockMvc.perform(get("/dns/zones"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0]").value("vaier.net"))
               .andExpect(jsonPath("$[1]").value("example.com"));
    }

    @Test
    void getDnsZones_returnsEmptyList() throws Exception {
        when(getDnsInfoUseCase.getDnsZones()).thenReturn(List.of());

        mockMvc.perform(get("/dns/zones"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void addDnsZone_delegatesToUseCase() throws Exception {
        mockMvc.perform(post("/dns/zones")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"newzone.com"}
                           """))
               .andExpect(status().isOk());

        ArgumentCaptor<AddDnsZoneUseCase.DnsZoneUco> captor =
                ArgumentCaptor.forClass(AddDnsZoneUseCase.DnsZoneUco.class);
        verify(addDnsZoneUseCase).addDnsZone(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("newzone.com");
    }

    @Test
    void deleteDnsZone_delegatesToUseCase() throws Exception {
        mockMvc.perform(delete("/dns/zones/example.com"))
               .andExpect(status().isOk());

        verify(deleteDnsZoneUseCase).deleteDnsZone("example.com");
    }

    @Test
    void getDnsRecords_returnsRecordNames() throws Exception {
        when(getDnsInfoUseCase.getDnsRecords(new DnsZoneUco("vaier.net"))).thenReturn(List.of(
                new DnsRecordUco("app.vaier.net"),
                new DnsRecordUco("api.vaier.net")
        ));

        mockMvc.perform(get("/dns/zones/vaier.net/records"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0]").value("app.vaier.net"))
               .andExpect(jsonPath("$[1]").value("api.vaier.net"));
    }

    @Test
    void addDnsRecord_delegatesToUseCaseWithCorrectValues() throws Exception {
        mockMvc.perform(post("/dns/zones/vaier.net/records")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "name":"app.vaier.net",
                             "type":"CNAME",
                             "ttl":300,
                             "values":["target.example.com"]
                           }
                           """))
               .andExpect(status().isOk());

        ArgumentCaptor<AddDnsRecordUseCase.DnsRecordUco> captor =
                ArgumentCaptor.forClass(AddDnsRecordUseCase.DnsRecordUco.class);
        verify(addDnsRecordUseCase).addDnsRecord(captor.capture(), eq("vaier.net"));
        assertThat(captor.getValue().name()).isEqualTo("app.vaier.net");
        assertThat(captor.getValue().type()).isEqualTo("CNAME");
        assertThat(captor.getValue().ttl()).isEqualTo(300L);
        assertThat(captor.getValue().values()).containsExactly("target.example.com");
    }

    @Test
    void deleteDnsRecord_delegatesToUseCaseWithCorrectValues() throws Exception {
        mockMvc.perform(delete("/dns/zones/vaier.net/records")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"app.vaier.net","type":"CNAME"}
                           """))
               .andExpect(status().isOk());

        verify(deleteDnsRecordUseCase).deleteDnsRecord("app.vaier.net", "CNAME", "vaier.net");
    }
}
