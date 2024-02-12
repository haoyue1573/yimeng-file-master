package com.qiwenshare.file.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qiwenshare.common.anno.MyLog;
import com.qiwenshare.common.result.RestResult;
import com.qiwenshare.common.util.DateUtil;
import com.qiwenshare.common.util.security.JwtUser;
import com.qiwenshare.common.util.security.SessionUtil;
import com.qiwenshare.file.api.IShareFileService;
import com.qiwenshare.file.api.IShareService;
import com.qiwenshare.file.api.IUserFileService;
import com.qiwenshare.file.component.FileDealComp;
import com.qiwenshare.file.domain.Share;
import com.qiwenshare.file.domain.ShareFile;
import com.qiwenshare.file.domain.UserFile;
import com.qiwenshare.file.dto.sharefile.*;
import com.qiwenshare.file.io.QiwenFile;
import com.qiwenshare.file.vo.share.ShareFileListVO;
import com.qiwenshare.file.vo.share.ShareFileVO;
import com.qiwenshare.file.vo.share.ShareListVO;
import com.qiwenshare.file.vo.share.ShareTypeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.text.ParseException;
import java.util.*;

@Tag(name = "share", description = "该接口为文件分享接口")
@RestController
@Slf4j
@RequestMapping("/share")
public class ShareController {

    public static final String CURRENT_MODULE = "文件分享";

    @Resource
    IShareFileService shareFileService;
    @Resource
    IShareService shareService;
    @Resource
    IUserFileService userFileService;
    @Resource
    FileDealComp fileDealComp;

    @Operation(summary = "分享文件", description = "分享文件统一接口", tags = {"share"})
    @PostMapping(value = "/sharefile")
    @MyLog(operation = "分享文件", module = CURRENT_MODULE)
    @ResponseBody
    public RestResult<ShareFileVO> shareFile( @RequestBody ShareFileDTO shareFileDTO) {
        ShareFileVO shareSecretVO = new ShareFileVO();
        JwtUser sessionUserBean = SessionUtil.getSession();

        String uuid = UUID.randomUUID().toString().replace("-", "");
        Share share = new Share();
        share.setShareId(IdUtil.getSnowflakeNextIdStr());
        BeanUtil.copyProperties(shareFileDTO, share);
        share.setShareTime(DateUtil.getCurrentTime());
        share.setUserId(sessionUserBean.getUserId());
        share.setShareStatus(0);
        if (shareFileDTO.getShareType() == 1) {
            String extractionCode = RandomUtil.randomNumbers(6);
            share.setExtractionCode(extractionCode);
            shareSecretVO.setExtractionCode(share.getExtractionCode());
        }

        share.setShareBatchNum(uuid);
        shareService.save(share);

        List<ShareFile> saveFileList = new ArrayList<>();
        String userFileIds = shareFileDTO.getUserFileIds();
        String[] userFileIdList = userFileIds.split(",");
        for (String userFileId : userFileIdList) {
            UserFile userFile = userFileService.getById(userFileId);
            if (userFile.getUserId().compareTo(sessionUserBean.getUserId()) != 0) {
                return RestResult.fail().message("您只能分享自己的文件");
            }
            if (userFile.getIsDir() == 1) {
                QiwenFile qiwenFile = new QiwenFile(userFile.getFilePath(), userFile.getFileName(), true);
                List<UserFile> userfileList = userFileService.selectUserFileByLikeRightFilePath(qiwenFile.getPath(), sessionUserBean.getUserId());
                for (UserFile userFile1 : userfileList) {
                    ShareFile shareFile1 = new ShareFile();
                    shareFile1.setShareFileId(IdUtil.getSnowflakeNextIdStr());
                    shareFile1.setUserFileId(userFile1.getUserFileId());
                    shareFile1.setShareBatchNum(uuid);
                    shareFile1.setShareFilePath(userFile1.getFilePath().replaceFirst(userFile.getFilePath().equals("/") ? "" : userFile.getFilePath(), ""));
                    saveFileList.add(shareFile1);
                }
            }
            ShareFile shareFile = new ShareFile();
            shareFile.setShareFileId(IdUtil.getSnowflakeNextIdStr());
            shareFile.setUserFileId(userFileId);
            shareFile.setShareFilePath("/");
            shareFile.setShareBatchNum(uuid);
            saveFileList.add(shareFile);


        }
        shareFileService.saveBatch(saveFileList);
        shareSecretVO.setShareBatchNum(uuid);

        return RestResult.success().data(shareSecretVO);
    }

    @Operation(summary = "保存分享文件", description = "用来将别人分享的文件保存到自己的网盘中", tags = {"share"})
    @PostMapping(value = "/savesharefile")
    @MyLog(operation = "保存分享文件", module = CURRENT_MODULE)
    @Transactional(rollbackFor=Exception.class)
    @ResponseBody
    public RestResult saveShareFile(@RequestBody SaveShareFileDTO saveShareFileDTO) {

        JwtUser sessionUserBean = SessionUtil.getSession();
//        List<ShareFile> fileList = JSON.parseArray(saveShareFileDTO.getFiles(), ShareFile.class);
        String savefilePath = saveShareFileDTO.getFilePath();
        String userId = sessionUserBean.getUserId();
        String[] userFileIdArr = saveShareFileDTO.getUserFileIds().split(",");
        List<UserFile> saveUserFileList = new ArrayList<>();
        for (String userFileId : userFileIdArr) {


            UserFile userFile = userFileService.getById(userFileId);
            String fileName = userFile.getFileName();
            String filePath = userFile.getFilePath();

            UserFile userFile2 = new UserFile();
            BeanUtil.copyProperties(userFile, userFile2);

            String savefileName = fileDealComp.getRepeatFileName(userFile, savefilePath);

            if (userFile.getIsDir() == 1) {
                ShareFile shareFile = shareFileService.getOne(new QueryWrapper<ShareFile>().lambda().eq(ShareFile::getUserFileId, userFileId).eq(ShareFile::getShareBatchNum, saveShareFileDTO.getShareBatchNum()));
                List<ShareFile> shareFileList = shareFileService.list(new QueryWrapper<ShareFile>().lambda().eq(ShareFile::getShareBatchNum, saveShareFileDTO.getShareBatchNum()).likeRight(ShareFile::getShareFilePath, QiwenFile.formatPath(shareFile.getShareFilePath() +"/"+ fileName)));



                for (ShareFile shareFile1 : shareFileList) {
                    UserFile userFile1 = userFileService.getById(shareFile1.getUserFileId());
                    userFile1.setUserFileId(IdUtil.getSnowflakeNextIdStr());
                    userFile1.setUserId(userId);
                    userFile1.setFilePath(userFile1.getFilePath().replaceFirst(QiwenFile.formatPath(filePath + "/" + fileName), QiwenFile.formatPath(savefilePath + "/" + savefileName)));
                    saveUserFileList.add(userFile1);
                    log.info("当前文件：" + JSON.toJSONString(userFile1));
                }
            }
            userFile2.setUserFileId(IdUtil.getSnowflakeNextIdStr());
            userFile2.setUserId(userId);
            userFile2.setFilePath(savefilePath);
            userFile2.setFileName(savefileName);
            saveUserFileList.add(userFile2);

        }
        log.info("----------" + JSON.toJSONString(saveUserFileList));
        userFileService.saveBatch(saveUserFileList);

        return RestResult.success();
    }

    @Operation(summary = "查看已分享列表", description = "查看已分享列表", tags = {"share"})
    @GetMapping(value = "/shareList")
    @ResponseBody
    public RestResult<ShareListVO> shareList(ShareListDTO shareListDTO) {
        JwtUser sessionUserBean = SessionUtil.getSession();
        List<ShareListVO> shareList = shareService.selectShareList(shareListDTO, sessionUserBean.getUserId());

        int total = shareService.selectShareListTotalCount(shareListDTO, sessionUserBean.getUserId());

        return RestResult.success().dataList(shareList, total);
    }


    @Operation(summary = "分享文件列表", description = "分享列表", tags = {"share"})
    @GetMapping(value = "/sharefileList")
    @ResponseBody
    public RestResult<ShareFileListVO> shareFileList(ShareFileListDTO shareFileListBySecretDTO) {
        String shareBatchNum = shareFileListBySecretDTO.getShareBatchNum();
        String shareFilePath = shareFileListBySecretDTO.getShareFilePath();
        List<ShareFileListVO> list = shareFileService.selectShareFileList(shareBatchNum, shareFilePath);
        for (ShareFileListVO shareFileListVO : list) {
            shareFileListVO.setShareFilePath(shareFilePath);
        }
        return RestResult.success().dataList(list, list.size());
    }

    @Operation(summary = "分享类型", description = "可用此接口判断是否需要提取码", tags = {"share"})
    @GetMapping(value = "/sharetype")
    @ResponseBody
    public RestResult<ShareTypeVO> shareType(ShareTypeDTO shareTypeDTO) {
        LambdaQueryWrapper<Share> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Share::getShareBatchNum, shareTypeDTO.getShareBatchNum());
        Share share = shareService.getOne(lambdaQueryWrapper);
        ShareTypeVO shareTypeVO = new ShareTypeVO();
        shareTypeVO.setShareType(share.getShareType());
        return RestResult.success().data(shareTypeVO);
    }

    @Operation(summary = "校验提取码", description = "校验提取码", tags = {"share"})
    @GetMapping(value = "/checkextractioncode")
    @ResponseBody
    public RestResult<String> checkExtractionCode(CheckExtractionCodeDTO checkExtractionCodeDTO) {
        LambdaQueryWrapper<Share> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Share::getShareBatchNum, checkExtractionCodeDTO.getShareBatchNum())
                .eq(Share::getExtractionCode, checkExtractionCodeDTO.getExtractionCode());
        List<Share> list = shareService.list(lambdaQueryWrapper);
        if (list.isEmpty()) {
            return RestResult.fail().message("校验失败");
        } else {
            return RestResult.success();
        }
    }

    @Operation(summary = "校验过期时间", description = "校验过期时间", tags = {"share"})
    @GetMapping(value = "/checkendtime")
    @ResponseBody
    public RestResult<String> checkEndTime(CheckEndTimeDTO checkEndTimeDTO) {
        LambdaQueryWrapper<Share> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Share::getShareBatchNum, checkEndTimeDTO.getShareBatchNum());
        Share share = shareService.getOne(lambdaQueryWrapper);
        if (share == null) {
            return RestResult.fail().message("文件不存在！");
        }
        String endTime = share.getEndTime();
        Date endTimeDate = null;
        try {
            endTimeDate = DateUtil.getDateByFormatString(endTime, "yyyy-MM-dd HH:mm:ss");
        } catch (ParseException e) {
            log.error("日期解析失败：{}" , e);
        }
        if (new Date().after(endTimeDate))  {
            return RestResult.fail().message("分享已过期");
        } else {
            return RestResult.success();
        }
    }
}
