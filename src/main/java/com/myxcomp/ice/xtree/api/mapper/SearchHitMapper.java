package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchHitMapper {

    public SearchHit toDto(SearchHitView view) {
        CachedNode node = view.node();
        SearchHit dto = new SearchHit(node.itemTreeId(), node.name(), node.type());
        dto.setPath(view.path());
        return dto;
    }

    public List<SearchHit> toDtos(List<SearchHitView> views) {
        List<SearchHit> out = new ArrayList<>(views.size());
        for (SearchHitView v : views) out.add(toDto(v));
        return out;
    }
}
