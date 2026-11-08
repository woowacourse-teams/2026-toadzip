package com.toadzip.backend.ingest.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.dto.RegisteredComplex;
import com.toadzip.backend.ingest.location.repository.external.JusoAddressClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AddressLookupRegisteredComplexIntegrationTest {

    private static final JusoAddress DONGTAN = new JusoAddress("경기도 화성시 동탄대로1길 96", "경기도 화성시 오산동 914",
            "4159011100", "415903000001", false, 96, 0, false, 914, 0);

    @Autowired private AddressLookupService service;
    @Autowired private HousingComplexRepository complexes;
    @MockitoBean private JusoAddressClient client;

    @AfterEach
    void cleanUp() {
        complexes.deleteAll(complexes.findAll().stream().filter(complex -> complex.getSourceComplexIdentifier().startsWith("lookup-")).toList());
    }

    @Test
    void 저장된_주소_표기가_달라도_같은_도로명_건물번호나_같은_필지의_단지를_알려_주고_휴지통과_다른_번호는_뺀다() {
        HousingComplex withDistrict = save("구 포함 표기", "경기도 화성시 동탄구 동탄대로1길 96", "4159711100109140000");
        HousingComplex abbreviated = save("시도 약칭 표기", "경기 화성시 동탄대로1길 96", "4159711100109150000");
        HousingComplex samePnu = save("같은 필지", "경기도 화성시 다른로 1", DONGTAN.pnu());
        save("다른 번호", "경기도 화성시 동탄대로1길 960", "4159711100109160000");
        save("같은 도 다른 시", "경기도 수원시 장안구 동탄대로1길 96", "4111112900100010000");
        HousingComplex trashed = save("휴지통", "경기도 화성시 동탄대로1길 96", "4159711100109170000");
        trashed.moveToTrash();
        complexes.save(trashed);
        when(client.search("동탄대로1길 96", 5)).thenReturn(List.of(DONGTAN));
        when(client.coordinate(any())).thenReturn(Optional.empty());

        List<RegisteredComplex> registered = service.lookup("동탄대로1길 96").getFirst().registeredComplexes();

        assertThat(registered).extracting(RegisteredComplex::housingComplexId)
                .containsExactlyInAnyOrder(withDistrict.getId(), abbreviated.getId(), samePnu.getId());
        assertThat(registered).allSatisfy(complex -> assertThat(complex.rentalType()).isEqualTo("PUBLIC_RENTAL"));
    }

    @Test
    void 같은_법정동의_원천_필지에_등록된_단지도_알려_주고_다른_시도의_같은_도로명_건물번호는_뺀다() {
        HousingComplex sourceLot = save("원천 필지", "경기도 화성시 다른로 2", "4159011100109990000");
        save("다른 시도", "서울특별시 중구 동탄대로1길 96", "1114010100100010000");
        save("다른 법정동 원천", "경기도 화성시 다른로 3", "4159099900100010000");
        when(client.search("동탄대로1길 96", 5)).thenReturn(List.of(DONGTAN));
        when(client.coordinate(any())).thenReturn(Optional.empty());

        List<RegisteredComplex> withSource = service.lookup("동탄대로1길 96", "4159011100109990000").getFirst().registeredComplexes();
        List<RegisteredComplex> otherDong = service.lookup("동탄대로1길 96", "4159099900100010000").getFirst().registeredComplexes();

        assertThat(withSource).extracting(RegisteredComplex::housingComplexId).containsExactly(sourceLot.getId());
        assertThat(otherDong).isEmpty();
    }

    private HousingComplex save(String name, String roadAddress, String pnu) {
        return complexes.save(HousingComplex.create(name, "lookup-" + UUID.randomUUID(), "PUBLIC_RENTAL",
                Address.create(roadAddress, pnu, pnu.substring(0, 10), pnu.substring(0, 2), pnu.substring(0, 5),
                        new BigDecimal("37.2"), new BigDecimal("127.07")),
                null, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, null));
    }
}
