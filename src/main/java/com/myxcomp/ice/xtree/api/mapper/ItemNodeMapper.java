package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ItemNodeMapper {

    private final TimeMapper timeMapper;

    public ItemNodeMapper(TimeMapper timeMapper) {
        this.timeMapper = timeMapper;
    }

    public ItemNode toDto(CachedNode node) {
        return new ItemNode(
                node.itemTreeId(),
                node.parentId(),
                node.name(),
                node.type(),
                timeMapper.toOffsetDateTime(node.lastUpdate()),
                node.lastUpdateUser());
    }

    public ItemNode toDto(TreeNodeView view) {
        ItemNode dto = toDto(view.node());
        dto.setPath(view.path());
        return dto;
    }

    public List<ItemNode> toDtos(List<TreeNodeView> views) {
        List<ItemNode> out = new ArrayList<>(views.size());
        for (TreeNodeView v : views) out.add(toDto(v));
        return out;
    }
}
