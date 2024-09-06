package net.binder.api.bin.service;

import lombok.RequiredArgsConstructor;
import net.binder.api.bin.dto.BinCreateRequest;
import net.binder.api.bin.dto.BinDetailResponse;
import net.binder.api.bin.dto.BinUpdateRequest;
import net.binder.api.bin.entity.Bin;
import net.binder.api.bin.entity.BinDetailProjection;
import net.binder.api.bin.entity.BinType;
import net.binder.api.bin.repository.BinRepository;
import net.binder.api.binmodification.entity.BinModification;
import net.binder.api.binmodification.entity.BinModificationStatus;
import net.binder.api.binmodification.repository.BinModificationRepository;
import net.binder.api.binregistration.entity.BinRegistration;
import net.binder.api.binregistration.entity.BinRegistrationStatus;
import net.binder.api.binregistration.repository.BinRegistrationRepository;
import net.binder.api.common.exception.BadRequestException;
import net.binder.api.common.exception.NotFoundException;
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

    private final BinRepository binRepository;

    private final BinRegistrationRepository binRegistrationRepository;

    private final BinModificationRepository binModificationRepository;

    private final MemberService memberService;


    public void requestBinRegistration(BinCreateRequest binCreateRequest, String email) {
        Member member = memberService.findByEmail(email);
        BinType type = BinType.getType(binCreateRequest.getType());

        Point point = getPoint(binCreateRequest.getLatitude(), binCreateRequest.getLongitude());
        BinRegistration binRegistration = getBinRegistration(member);

        Bin bin = getBin(binCreateRequest, type, point);
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

        if (email == null) {
            return BinDetailResponse.from(bin);
        }
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

    public void requestBinModification(String email, Long binId, BinUpdateRequest binUpdateRequest) {
        Member member = memberService.findByEmail(email);
        Bin bin = findById(binId);

        validateBinOwner(bin, member);
        validateBinStatus(bin);

        BinType type = BinType.getType(binUpdateRequest.getType());
        BinModification binModification = getBinModification(binUpdateRequest, member, bin, type);

        binModificationRepository.save(binModification);
    }

    private Bin getBin(BinCreateRequest binCreateRequest, BinType type, Point point) {
        return Bin.builder()
                .title(binCreateRequest.getTitle())
                .address(binCreateRequest.getAddress())
                .type(type)
                .imageUrl(binCreateRequest.getImageUrl())
                .likeCount(0L)
                .dislikeCount(0L)
                .bookmarkCount(0L)
                .point(point)
                .build();
    }

    private Point getPoint(Double latitude, Double longitude) {
        GeometryFactory geometryFactory = new GeometryFactory();
        Coordinate coordinate = new Coordinate(longitude, latitude);
        return geometryFactory.createPoint(coordinate);
    }

    private BinRegistration getBinRegistration(Member member) {
        return BinRegistration.builder()
                .member(member)
                .status(BinRegistrationStatus.PENDING)
                .build();
    }

    private void validateBinOwner(Bin bin, Member member) {
        if (!bin.isOwner(member)) {
            throw new BadRequestException("해당 쓰레기통을 등록한 사람만 수정 요청이 가능합니다.");
        }
    }

    private void validateBinStatus(Bin bin) {
        if (bin.isPending()) {
            throw new BadRequestException("아직 등록 심사중인 쓰레기통입니다.");
        }
    }

    private BinModification getBinModification(BinUpdateRequest binUpdateRequest, Member member, Bin bin,
                                               BinType type) {
        return BinModification.builder()
                .member(member)
                .bin(bin)
                .title(binUpdateRequest.getTitle())
                .address(binUpdateRequest.getAddress())
                .type(type)
                .imageUrl(bin.getImageUrl())
                .latitude(binUpdateRequest.getLatitude())
                .longitude(binUpdateRequest.getLongitude())
                .status(BinModificationStatus.PENDING)
                .build();
    }
}
