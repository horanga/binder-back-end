package net.binder.api.bin.service;

import lombok.RequiredArgsConstructor;
import net.binder.api.bin.dto.BinCreateRequest;
import net.binder.api.bin.dto.BinDetailResponse;
import net.binder.api.bin.dto.UserBinUpdateRequest;
import net.binder.api.bin.entity.Bin;
import net.binder.api.bin.entity.BinDetailProjection;
import net.binder.api.bin.entity.BinModification;
import net.binder.api.bin.entity.BinModificationStatus;
import net.binder.api.bin.entity.BinRegistration;
import net.binder.api.bin.entity.BinRegistrationStatus;
import net.binder.api.bin.repository.BinModificationRepository;
import net.binder.api.bin.repository.BinRegistrationRepository;
import net.binder.api.bin.repository.BinRepository;
import net.binder.api.bin.util.DistanceCalculator;
import net.binder.api.bin.util.PointUtil;
import net.binder.api.common.exception.BadRequestException;
import net.binder.api.common.exception.NotFoundException;
import net.binder.api.common.kakaomap.service.KakaoMapService;
import net.binder.api.complaint.service.ComplaintCountReader;
import net.binder.api.member.entity.Member;
import net.binder.api.member.service.MemberService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BinService {

    private static final double OFFSET = 0.001;

    private final BinRepository binRepository;

    private final BinRegistrationRepository binRegistrationRepository;

    private final BinModificationRepository binModificationRepository;

    private final MemberService memberService;

    private final ComplaintCountReader complaintCountReader;

    private final KakaoMapService kakaoMapService;


    public void requestBinRegistration(BinCreateRequest binCreateRequest, String email) {
        Member member = memberService.findByEmail(email);

        Point point = getPoint(binCreateRequest.getAddress(), binCreateRequest.getLongitude(), binCreateRequest.getLatitude());
        BinRegistration binRegistration = getBinRegistration(member);

        Bin bin = getBin(binCreateRequest, point);
        bin.setBinRegistration(binRegistration);

        try {
            binRepository.save(bin);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("이미 등록된 쓰레기통입니다.");

        }

        binRegistrationRepository.save(binRegistration);
    }

    @Transactional(readOnly = true)
    public BinDetailResponse getBinDetail(String email, Long binId) {
        Bin bin = findById(binId);

        if (email == null) { // 비로그인 유저
            Long complaintCount = complaintCountReader.getComplaintCount(bin);

            return BinDetailResponse.from(bin, complaintCount);
        }
        // 로그인 유저
        Member member = memberService.findByEmail(email);

        BinDetailProjection projection = binRepository.findDetailByIdAndMemberIdNative(binId, member.getId()).
                orElseThrow(() ->
                        new NotFoundException("존재하지 않는 쓰레기통입니다."));

        return BinDetailResponse.from(projection);
    }

    @Transactional(readOnly = true)
    public Bin findById(Long binId) {

        return binRepository.findByIdAndDeletedAtIsNull(binId).
                orElseThrow(() ->
                        new NotFoundException("존재하지 않는 쓰레기통입니다."));
    }

    public void requestBinModification(String email, Long binId, UserBinUpdateRequest userBinUpdateRequest) {
        Member member = memberService.findByEmail(email);
        Bin bin = findById(binId);

        validateBinStatus(bin);
        validatePendingModification(bin);

        BinModification binModification = getBinModification(userBinUpdateRequest, member, bin);

        binModificationRepository.save(binModification);
    }

    private Bin getBin(BinCreateRequest binCreateRequest, Point point) {
        return Bin.builder()
                .title(binCreateRequest.getTitle())
                .address(binCreateRequest.getAddress())
                .type(binCreateRequest.getType())
                .imageUrl(binCreateRequest.getImageUrl())
                .likeCount(0L)
                .dislikeCount(0L)
                .bookmarkCount(0L)
                .point(point)
                .build();
    }

    private Point getPoint(String address, Double longitude, Double latitude) {
        Point point = kakaoMapService.getPoint(address);
        double distance = DistanceCalculator.calculateDistance(point.getY(), point.getX(), latitude, longitude);

        if(distance>=500){
            throw new BadRequestException("지정한 위치와 좌표가 일치하지 않습니다.");
        }

        return PointUtil.getPoint(longitude, latitude);
    }

    private BinRegistration getBinRegistration(Member member) {
        return BinRegistration.builder()
                .member(member)
                .status(BinRegistrationStatus.PENDING)
                .build();
    }

    private void validateBinStatus(Bin bin) {
        if (bin.isPending()) {
            throw new BadRequestException("아직 등록 심사중인 쓰레기통입니다.");
        }
    }

    private BinModification getBinModification(UserBinUpdateRequest userBinUpdateRequest, Member member, Bin bin) {
        return BinModification.builder()
                .member(member)
                .bin(bin)
                .title(userBinUpdateRequest.getTitle())
                .address(userBinUpdateRequest.getAddress())
                .type(userBinUpdateRequest.getType())
                .imageUrl(userBinUpdateRequest.getImageUrl())
                .latitude(userBinUpdateRequest.getLatitude())
                .longitude(userBinUpdateRequest.getLongitude())
                .status(BinModificationStatus.PENDING)
                .modificationReason(userBinUpdateRequest.getModificationReason())
                .build();
    }

    private void validatePendingModification(Bin bin) {
        if (binModificationRepository.existsByBinIdAndStatus(bin.getId(), BinModificationStatus.PENDING)) {
            throw new BadRequestException("아직 처리되지 않는 수정 요청 건이 존재합니다.");
        }
    }
}
