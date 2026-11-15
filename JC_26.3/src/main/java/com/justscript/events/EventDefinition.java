package com.justscript.events;

/**
 * Событие из каталога JustScript.
 *
 * @param id          имя в JustScript (то, что пишется после {@code @event})
 * @param justmc      ID события в JustMC (то, что попадает в JSON-модуль)
 * @param scope       BLOCK / ENTITY / PLAYER / WORLD
 * @param name        человекочитаемое название
 * @param description краткое описание
 * @param cancellable можно ли отменить событие на стороне JustMC
 */
public record EventDefinition(String id, String justmc, String scope, String name, String description,
		boolean cancellable) {
}
