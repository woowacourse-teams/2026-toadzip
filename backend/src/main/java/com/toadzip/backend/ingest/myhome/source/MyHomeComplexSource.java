package com.toadzip.backend.ingest.myhome.source;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.myhome.MyHomeComplexSourceItem;

@Entity
@Table(name = "myhome_complex_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MyHomeComplexSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_key", nullable = false, unique = true, length = 500)
	private String sourceKey;

	private Long hsmpSn;

	private String insttNm;

	private String brtcCode;

	private String brtcNm;

	private String signguCode;

	private String signguNm;

	private String hsmpNm;

	private String rnAdres;

	private String pnu;

	private String competDe;

	private Integer hshldCo;

	private String suplyTyNm;

	private String styleNm;

	private BigDecimal suplyPrvuseAr;

	private BigDecimal suplyCmnuseAr;

	private String houseTyNm;

	private String heatMthdDetailNm;

	private String buldStleNm;

	private String elvtrInstlAtNm;

	private Integer parkngCo;

	private Long bassRentGtn;

	private Long bassMtRntchrg;

	private Long bassCnvrsGtnLmt;

	private MyHomeComplexSource(MyHomeComplexSourceItem item) {
		sourceKey = sourceKeyOf(item);
		copyValues(item);
	}

	public static MyHomeComplexSource from(MyHomeComplexSourceItem item) {
		return new MyHomeComplexSource(item);
	}

	public static String sourceKeyOf(MyHomeComplexSourceItem item) {
		return keyPart(item.hsmpSn()) + keyPart(item.pnu()) + keyPart(item.suplyTyNm()) + keyPart(item.styleNm());
	}

	public boolean replaceWith(MyHomeComplexSourceItem item) {
		MyHomeComplexSource incoming = from(item);
		if (toItem().equals(incoming.toItem())) {
			return false;
		}
		copyValues(incoming.toItem());
		return true;
	}

	public MyHomeComplexSourceItem toItem() {
		return new MyHomeComplexSourceItem(hsmpSn, insttNm, brtcCode, brtcNm, signguCode, signguNm, hsmpNm, rnAdres,
				pnu, competDe, hshldCo, suplyTyNm, styleNm, suplyPrvuseAr, suplyCmnuseAr, houseTyNm, heatMthdDetailNm,
				buldStleNm, elvtrInstlAtNm, parkngCo, bassRentGtn, bassMtRntchrg, bassCnvrsGtnLmt);
	}

	private void copyValues(MyHomeComplexSourceItem item) {
		hsmpSn = item.hsmpSn();
		insttNm = SourceValues.trimToNull(item.insttNm());
		brtcCode = SourceValues.trimToNull(item.brtcCode());
		brtcNm = SourceValues.trimToNull(item.brtcNm());
		signguCode = SourceValues.trimToNull(item.signguCode());
		signguNm = SourceValues.trimToNull(item.signguNm());
		hsmpNm = SourceValues.trimToNull(item.hsmpNm());
		rnAdres = SourceValues.trimToNull(item.rnAdres());
		pnu = SourceValues.trimToNull(item.pnu());
		competDe = SourceValues.trimToNull(item.competDe());
		hshldCo = item.hshldCo();
		suplyTyNm = SourceValues.trimToNull(item.suplyTyNm());
		styleNm = SourceValues.trimToNull(item.styleNm());
		suplyPrvuseAr = item.suplyPrvuseAr();
		suplyCmnuseAr = item.suplyCmnuseAr();
		houseTyNm = SourceValues.trimToNull(item.houseTyNm());
		heatMthdDetailNm = SourceValues.trimToNull(item.heatMthdDetailNm());
		buldStleNm = SourceValues.trimToNull(item.buldStleNm());
		elvtrInstlAtNm = SourceValues.trimToNull(item.elvtrInstlAtNm());
		parkngCo = item.parkngCo();
		bassRentGtn = item.bassRentGtn();
		bassMtRntchrg = item.bassMtRntchrg();
		bassCnvrsGtnLmt = item.bassCnvrsGtnLmt();
	}

	private static String keyPart(Object raw) {
		String value = null;
		if (raw != null) {
			value = SourceValues.trimToNull(raw.toString());
		}
		if (value == null) {
			return "-1:";
		}
		return value.length() + ":" + value;
	}

}
