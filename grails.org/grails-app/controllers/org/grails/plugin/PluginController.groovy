

package org.grails.plugin

import org.grails.wiki.WikiPage
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.grails.wiki.BaseWikiController
import org.grails.taggable.*
import org.grails.comments.*

class PluginController extends BaseWikiController {

    static String HOME_WIKI = 'PluginHome'
    static int PORTAL_MAX_RESULTS = 5
    static int PORTAL_MIN_RATINGS = 3

    def wikiPageService

    def index = {
        redirect(controller:'plugin', action:home, params:params)
    }

    def home = {

        def tagCounts = [:]
        TagLink.withCriteria {
            eq('type', 'plugin')
            projections {
                groupProperty('tag')
                count('tagRef')
            }
        }.each {
            // TODO: put multiple assignment back in place as soon as IntelliJ catches up, because right now it thinks
            // this entire source file is invalid when I do this:
            //      def (tagName, count) = it
            def tagName = it[0]
            def count = it[1]
            tagCounts[tagName] = tagCounts[tagName] ? (tagCounts[tagName] + count) : count
        }

        def popularPlugins = Plugin.list().findAll {
            it.ratings.size() >= PORTAL_MIN_RATINGS
        }.sort {
            it.averageRating
        }.reverse()
        // only the first few
        if (popularPlugins.size()) {
            popularPlugins = popularPlugins[0..(popularPlugins.size() < PORTAL_MAX_RESULTS ? popularPlugins.size() - 1 : PORTAL_MAX_RESULTS - 1)]
        }

        def newestPlugins = Plugin.withCriteria {
            order('dateCreated', 'desc')
            maxResults(PORTAL_MAX_RESULTS)
        }

        def recentlyUpdatedPlugins = Plugin.withCriteria {
            order('lastReleased', 'desc')
            maxResults(PORTAL_MAX_RESULTS)
        }

        def latestComments = CommentLink.withCriteria {
            eq 'type', 'plugin'
            comment {
                order('dateCreated', 'desc')
            }
            maxResults PORTAL_MAX_RESULTS
        }*.comment
    
        def homeWiki = wikiPageService.getCachedOrReal(HOME_WIKI)
        if (!homeWiki) {
            homeWiki = new WikiPage(title:HOME_WIKI, body: 'Please edit me.').save()
        }
        [
                homeWiki: homeWiki,
                tagCounts: tagCounts,
                popularPlugins: popularPlugins,
                newestPlugins: newestPlugins,
                recentlyUpdatedPlugins: recentlyUpdatedPlugins,
                latestComments: latestComments
        ]
    }

    def list = {
        def pluginMap = [:]
        Tag.list(sort:'name').each { tag ->
            pluginMap[tag.name] = []
            def links = TagLink.withCriteria {
                eq('tag', tag)
                eq('type', 'plugin')
            }
            links.each { link ->
                def p = Plugin.get(link.tagRef)
                if (p) pluginMap[tag.name] << p 
            }
            pluginMap[tag.name].sort { it.title }
        }
        // remove empty tags
        pluginMap = pluginMap.findAll { it.value.size() }
        def taggedIds = TagLink.withCriteria {
            eq('type', 'plugin')
            projections {
                distinct('tagRef')
            }
        }
        pluginMap.untagged = Plugin.findAllByIdNotInList(taggedIds)
        render view:'listPlugins', model:[pluginMap: pluginMap]
    }

    def show = {
        def plugin = byName(params)
        if (!plugin) {
            return redirect(action:'createPlugin', params:params)
        }

        def userRating
        if (request.user) {
            userRating = plugin.userRating(request.user)
        }

        // TODO: figure out why plugin.ratings.size() is always 1
        render view:'showPlugin', model:[plugin:plugin, userRating: userRating]
    }

    def editPlugin = {
        def plugin = Plugin.get(params.id)
        if(plugin) {
            if(request.method == 'POST') {
                if (params.currentRelease && plugin.currentRelease != params.currentRelease) {
                    plugin.lastReleased = new Date();
                }
                // update plugin
                plugin.properties = params
                plugin.save(flush:true)
                redirect(action:'show', params:[name:plugin.name])
            } else {
                return render(view:'editPlugin', model: [plugin:plugin])
            }
        } else {
            response.sendError 404
        }
    }

    def createPlugin = {
        // just in case this was an ad hoc creation where the user logged in during the creation...
        if (params.name) params.name = params.name - '?action=login'
        def plugin = new Plugin(params)
        if(request.method == 'POST') {
            plugin.save(flush:true)
            Plugin.WIKIS.each { wiki ->
                def body = ''
                if (wiki == 'installation') {
                    body = "{code}grails install-plugin ${plugin.name}{code}"
                }
                def wikiPage = new WikiPage(title:"${wiki}-${plugin.id}", body:body)
                wikiPage.save()
                plugin."$wiki" = wikiPage
            }

            // if there is no provided doc url, we'll assume that this page is the doc
            if (!plugin.documentationUrl) {
                plugin.documentationUrl = "${ConfigurationHolder.config.grails.serverURL}/plugin/${plugin.name}"
            }

            plugin.author = request.user
            plugin.lastReleased = new Date()
            if(plugin.save()) {
                redirect(action:'show', params: [name:plugin.name])
            } else {
                return render(view:'createPlugin', model:[plugin:plugin])
            }
        } else {
            return render(view:'createPlugin', model:[plugin:plugin])
        }
    }

    def deletePlugin = {
        def plugin = byName(params)
        log.warn "Deleting Plugin: $plugin"
        plugin.delete()
        redirect(view:'index')
    }

    def search = {
		if(params.q) {
            def searchResult = Plugin.search(params.q, reload: true, offset: params.offset, escape:true)
            searchResult.results = searchResult.results.findAll{it}.unique { it.title }
			flash.message = "Found $searchResult.total results!"
			flash.next()
			render(view:"searchResults", model:[searchResult:searchResult])
		}
		else {
			redirect(action:'home')
		}
   }

    def latest = {

        def engine = createWikiEngine()

         def feedOutput = {

            def top5 = Plugin.listOrderByLastUpdated(order:'desc', max:5)
            title = "Grails New Plugins Feed"
            link = "http://grails.org/Plugins"
            description = "New and recently updated Grails Plugins"

            for(item in top5) {
                entry(item.title) {
                    link = "http://grails.org/plugin/${item.name.encodeAsURL()}"
                    author = item.author
                    publishedDate = item.lastUpdated
                    engine.render(item.description.body, context)
                }
            }
         }

        withFormat {
            html {
                redirect(uri:"")
            }
            rss {
                render(feedType:"rss",feedOutput)
            }
            atom {
                render(feedType:"atom", feedOutput)
            }
        }

    }

    def postComment = {
        def plugin = Plugin.get(params.id)
        plugin.addComment(request.user, params.comment)
        plugin.save(flush:true)
        return render(template:'/comments/comment', var:'comment', bean:plugin.comments[-1])
    }

    def addTag = {
        def plugin = Plugin.get(params.id)
        params.newTag.trim().split(',').each { newTag ->
            plugin.addTag(newTag.trim())
        }
        assert plugin.save()
        render(template:'tags', var:'plugin', bean:plugin)
    }

    def removeTag = {
        def plugin = Plugin.get(params.id)
        plugin.removeTag(params.tagName)
        plugin.save()
        render(template:'tags', var:'plugin', bean:plugin)
    }

    def showTag = {
        redirect(action:'list', fragment:"${params.selectedTag} tags")
    }

    def showComment = {
        def link = CommentLink.findByCommentAndType(Comment.get(params.id), 'plugin')
        def plugin = Plugin.get(link.commentRef)
        redirect(action:'show', params:[name:plugin.name], fragment:"comment_${params.id}")
    }

    private def pluginWiki(name, plugin, params) {
        plugin."$name" = new WikiPage(title:name, body:params."$name")
    }

    private def byTitle(params) {
        Plugin.findByTitle(params.title.replaceAll('\\+', ' '))
    }

    private def byName(params) {
        Plugin.findByName(params.name)
    }
}
