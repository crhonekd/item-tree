package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchHitMapper {

    public SearchHit toDto(CachedNode node) {
        return new SearchHit(node.itemTreeId(), node.name(), node.type());
    }

    public List<SearchHit> toDtos(List<CachedNode> nodes) {
        List<SearchHit> out = new ArrayList<>(nodes.size());
        for (CachedNode n : nodes) out.add(toDto(n));
        return out;
    }
}
