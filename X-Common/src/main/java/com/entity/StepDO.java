package com.entity;

import lombok.Data;

import java.util.List;

@Data
public class StepDO {

    String alias;

    boolean extract; //提取自身

    List<FieldDO> links; //转换为其他链接
}
