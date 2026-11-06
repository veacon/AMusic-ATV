package com.applemusicktv.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private fun itemIdentity(key: Any?, index: Int) = key?.let { "${it::class.java.name}:$it" } ?: "index:$index"

private class ReturnListScope(
    private val delegate: LazyListScope, private val page: ReturnPageState?, private val id: Int,
    private val path: List<ReturnStep>, private val state: LazyListState,
) : LazyListScope by delegate {
    private val keys=mutableListOf<String>()
    private fun update() { page?.lists?.set(id,ReturnList(keys.toList(),{ state.layoutInfo.visibleItemsInfo.map {it.index}.toSet() },{state.scrollToItem(it)})) }
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val index=keys.size;val identity=itemIdentity(key,index);keys+=identity;update()
        delegate.item(key,contentType) {
            CompositionLocalProvider(LocalReturnPath provides this@ReturnListScope.path+ReturnStep(this@ReturnListScope.id,identity,index)) { content() }
        }
    }
    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        val start=keys.size;val identities=(0 until count).map { itemIdentity(key?.invoke(it),start+it) };keys+=identities;update()
        delegate.items(count,key,contentType) { index ->
            CompositionLocalProvider(LocalReturnPath provides this@ReturnListScope.path+ReturnStep(this@ReturnListScope.id,identities[index],start+index)) { itemContent(index) }
        }
    }
}

@Composable
internal fun ReturnLazyColumn(
    modifier: Modifier=Modifier, state: LazyListState=rememberLazyListState(),
    contentPadding: PaddingValues=PaddingValues(0.dp), reverseLayout: Boolean=false,
    verticalArrangement: Arrangement.Vertical=if(!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal=Alignment.Start, userScrollEnabled: Boolean=true,
    content: LazyListScope.() -> Unit,
) {
    val page=LocalReturnPage.current;val path=LocalReturnPath.current;val id=currentCompositeKeyHash
    DisposableEffect(page,id) { onDispose {page?.lists?.remove(id)} }
    LazyColumn(modifier,state,contentPadding,reverseLayout,verticalArrangement,horizontalAlignment,userScrollEnabled=userScrollEnabled) {
        ReturnListScope(this,page,id,path,state).content()
    }
}
@Composable
internal fun ReturnLazyRow(
    modifier: Modifier=Modifier, state: LazyListState=rememberLazyListState(),
    contentPadding: PaddingValues=PaddingValues(0.dp), reverseLayout: Boolean=false,
    horizontalArrangement: Arrangement.Horizontal=if(!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical=Alignment.Top, userScrollEnabled: Boolean=true,
    content: LazyListScope.() -> Unit,
) {
    val page=LocalReturnPage.current;val path=LocalReturnPath.current;val id=currentCompositeKeyHash
    DisposableEffect(page,id) { onDispose {page?.lists?.remove(id)} }
    LazyRow(modifier,state,contentPadding,reverseLayout,horizontalArrangement,verticalAlignment,userScrollEnabled=userScrollEnabled) {
        ReturnListScope(this,page,id,path,state).content()
    }
}
internal class ReturnGridScope(
    private val delegate: LazyGridScope, private val page: ReturnPageState?, private val id: Int,
    private val path: List<ReturnStep>, private val state: LazyGridState,
) {
    private val keys=mutableListOf<String>()
    private fun update() { page?.lists?.set(id,ReturnList(keys.toList(),{state.layoutInfo.visibleItemsInfo.map {it.index}.toSet()},{state.scrollToItem(it)})) }
    fun item(key: Any? = null, span: (LazyGridItemSpanScope.() -> GridItemSpan)? = null, contentType: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) {
        val index=keys.size;val identity=itemIdentity(key,index);keys+=identity;update()
        delegate.item(key,span,contentType) { CompositionLocalProvider(LocalReturnPath provides this@ReturnGridScope.path+ReturnStep(this@ReturnGridScope.id,identity,index)) { content() } }
    }
    fun items(count: Int,key: ((Int) -> Any)? = null,span: (LazyGridItemSpanScope.(Int) -> GridItemSpan)? = null,contentType: (Int) -> Any? = { null },itemContent: @Composable LazyGridItemScope.(Int) -> Unit) {
        val start=keys.size;val identities=(0 until count).map {itemIdentity(key?.invoke(it),start+it)};keys+=identities;update()
        delegate.items(count,key,span,contentType) { index -> CompositionLocalProvider(LocalReturnPath provides this@ReturnGridScope.path+ReturnStep(this@ReturnGridScope.id,identities[index],start+index)) { itemContent(index) } }
    }
}
@Composable
internal fun ReturnLazyVerticalGrid(
    columns: GridCells, modifier: Modifier=Modifier, state: LazyGridState=rememberLazyGridState(),
    contentPadding: PaddingValues=PaddingValues(0.dp), reverseLayout: Boolean=false,
    verticalArrangement: Arrangement.Vertical=if(!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalArrangement: Arrangement.Horizontal=Arrangement.Start, userScrollEnabled: Boolean=true,
    content: ReturnGridScope.() -> Unit,
) {
    val page=LocalReturnPage.current;val path=LocalReturnPath.current;val id=currentCompositeKeyHash
    DisposableEffect(page,id) { onDispose {page?.lists?.remove(id)} }
    LazyVerticalGrid(columns,modifier,state,contentPadding,reverseLayout,verticalArrangement,horizontalArrangement,userScrollEnabled=userScrollEnabled) {
        ReturnGridScope(this,page,id,path,state).content()
    }
}

/** Duplicate songs remain distinct while sorting other songs does not change their identity. */
internal fun occurrenceKeys(ids: List<String>): List<String> {
    val counts=mutableMapOf<String,Int>()
    return ids.map { id -> val occurrence=counts[id] ?: 0;counts[id]=occurrence+1;"${id.length}:$id:$occurrence" }
}

internal fun <T> ReturnGridScope.items(items: List<T>, key: ((T) -> Any)? = null, itemContent: @Composable LazyGridItemScope.(T) -> Unit) {
    items(items.size, key=key?.let { k -> { i -> k(items[i]) } }) { i -> itemContent(items[i]) }
}
internal fun <T> ReturnGridScope.itemsIndexed(items: List<T>, key: ((Int,T) -> Any)? = null, itemContent: @Composable LazyGridItemScope.(Int,T) -> Unit) {
    items(items.size,key=key?.let { k -> { i -> k(i,items[i]) } }) { i -> itemContent(i,items[i]) }
}
