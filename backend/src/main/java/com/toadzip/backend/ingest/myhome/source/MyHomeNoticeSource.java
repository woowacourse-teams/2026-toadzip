package com.toadzip.backend.ingest.myhome.source;

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
import com.toadzip.backend.ingest.myhome.MyHomeNoticeSourceItem;

@Entity
@Table(name = "myhome_notice_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MyHomeNoticeSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_key", nullable = false, unique = true, length = 500)
	private String sourceKey;

	@Column(nullable = false)
	private Integer sourceOrder;

	private String pblancId;

	private Integer houseSn;

	private String sttusNm;

	private String pblancNm;

	private String suplyInsttNm;

	private String houseTyNm;

	private String suplyTyNm;

	private String beforePblancId;

	private String rcritPblancDe;

	private String przwnerPresnatnDe;

	private String beginDe;

	private String endDe;

	@Column(length = 2000)
	private String refrnc;

	@Column(length = 2000)
	private String url;

	@Column(length = 2000)
	private String pcUrl;

	@Column(length = 2000)
	private String mobileUrl;

	private String hsmpNm;

	private String brtcNm;

	private String signguNm;

	@Column(length = 1000)
	private String fullAdres;

	private String rnCodeNm;

	private String refrnLegaldongNm;

	private String pnu;

	private String heatMthdNm;

	private String totHshldCo;

	private Integer sumSuplyCo;

	private Long rentGtn;

	private Long enty;

	private Long surlus;

	private Long mtRntchrg;

	private MyHomeNoticeSource(int sourceOrder, MyHomeNoticeSourceItem item) {
		this.sourceOrder = sourceOrder;
		sourceKey = sourceKeyOf(item);
		copyValues(item);
	}

	public static MyHomeNoticeSource from(int sourceOrder, MyHomeNoticeSourceItem item) {
		return new MyHomeNoticeSource(sourceOrder, item);
	}

	public static String sourceKeyOf(MyHomeNoticeSourceItem item) {
		return keyPart(SourceValues.trimToNull(item.pblancId())) + keyPart(item.houseSn());
	}

	public static boolean hasSameValues(MyHomeNoticeSourceItem first, MyHomeNoticeSourceItem second) {
		return from(0, first).toItem().equals(from(0, second).toItem());
	}

	public boolean hasSameValues(MyHomeNoticeSourceItem item) {
		return toItem().equals(from(sourceOrder, item).toItem());
	}

	public MyHomeNoticeSourceItem toItem() {
		return new MyHomeNoticeSourceItem(pblancId, houseSn, sttusNm, pblancNm, suplyInsttNm, houseTyNm, suplyTyNm,
				beforePblancId, rcritPblancDe, przwnerPresnatnDe, beginDe, endDe, refrnc, url, pcUrl, mobileUrl, hsmpNm,
				brtcNm, signguNm, fullAdres, rnCodeNm, refrnLegaldongNm, pnu, heatMthdNm, totHshldCo, sumSuplyCo,
				rentGtn, enty, surlus, mtRntchrg);
	}

	private void copyValues(MyHomeNoticeSourceItem item) {
		pblancId = SourceValues.trimToNull(item.pblancId());
		houseSn = item.houseSn();
		sttusNm = SourceValues.trimToNull(item.sttusNm());
		pblancNm = SourceValues.trimToNull(item.pblancNm());
		suplyInsttNm = SourceValues.trimToNull(item.suplyInsttNm());
		houseTyNm = SourceValues.trimToNull(item.houseTyNm());
		suplyTyNm = SourceValues.trimToNull(item.suplyTyNm());
		beforePblancId = SourceValues.trimToNull(item.beforePblancId());
		rcritPblancDe = SourceValues.trimToNull(item.rcritPblancDe());
		przwnerPresnatnDe = SourceValues.trimToNull(item.przwnerPresnatnDe());
		beginDe = SourceValues.trimToNull(item.beginDe());
		endDe = SourceValues.trimToNull(item.endDe());
		refrnc = SourceValues.trimToNull(item.refrnc());
		url = SourceValues.trimToNull(item.url());
		pcUrl = SourceValues.trimToNull(item.pcUrl());
		mobileUrl = SourceValues.trimToNull(item.mobileUrl());
		hsmpNm = SourceValues.trimToNull(item.hsmpNm());
		brtcNm = SourceValues.trimToNull(item.brtcNm());
		signguNm = SourceValues.trimToNull(item.signguNm());
		fullAdres = SourceValues.trimToNull(item.fullAdres());
		rnCodeNm = SourceValues.trimToNull(item.rnCodeNm());
		refrnLegaldongNm = SourceValues.trimToNull(item.refrnLegaldongNm());
		pnu = SourceValues.trimToNull(item.pnu());
		heatMthdNm = SourceValues.trimToNull(item.heatMthdNm());
		totHshldCo = SourceValues.trimToNull(item.totHshldCo());
		sumSuplyCo = item.sumSuplyCo();
		rentGtn = item.rentGtn();
		enty = item.enty();
		surlus = item.surlus();
		mtRntchrg = item.mtRntchrg();
	}

	private static String keyPart(Object raw) {
		if (raw == null) {
			return "-1:";
		}
		String value = raw.toString();
		return value.length() + ":" + value;
	}

}
